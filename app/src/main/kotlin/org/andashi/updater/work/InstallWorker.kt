package org.andashi.updater.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.andashi.updater.Notifications
import org.andashi.updater.UpdaterApp
import org.andashi.updater.core.AppState
import org.andashi.updater.core.Backoff
import org.andashi.updater.Debug
import org.andashi.updater.core.Config
import org.andashi.updater.core.Decide
import org.andashi.updater.core.Lock
import org.andashi.updater.install.SessionInstaller
import org.andashi.updater.net.Fetcher
import org.andashi.updater.net.HttpFetcher
import org.andashi.updater.pm.AndroidPackages
import org.andashi.updater.store.Store
import java.time.Instant

/**
 * The install run (design §7, steps 3-6), inside a dataSync foreground
 * service: for every package the last check found `behind` and due,
 * download, verify, install, and wait for the system's answer. One package
 * at a time; a failure moves on to the next.
 *
 * It re-decides from the lock on disk rather than trusting the check's
 * list: between the two runs another zone may have updated the package.
 */
class InstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = Notifications.foregroundInfo(applicationContext, "Checking for updates")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val store = UpdaterApp.store(context)
        try {
            setForeground(Notifications.foregroundInfo(context, "Downloading updates"))
        } catch (e: Exception) {
            // Without the exemption this is where it ends (measured 2026-10-09).
            store.update { it.copy(exemption = "missing", lastCheckError = "foreground service refused: ${e.message}") }
            Notifications.lockProblem(context, "The updater may not run in the background: ${e.javaClass.simpleName}. The host must grant the battery exemption.")
            return@withContext Result.failure()
        }
        val config = store.configText()?.let { runCatching { Config.parse(it, Debug.cleartextLocalhost(applicationContext)) }.getOrNull() } ?: return@withContext Result.success()
        val lockText = (if (config.hostOnly) store.hostLockText() else store.lockText()) ?: return@withContext Result.success()
        val lock = runCatching { Lock.parse(lockText, config.lockPolicy(Debug.cleartextLocalhost(applicationContext))) }.getOrNull() ?: return@withContext Result.success()
        val lockSha = store.state().lock.sha256
        val packages = AndroidPackages(context)
        val installer = SessionInstaller(context, packages)
        val fetcher = HttpFetcher(config.lockPolicy(Debug.cleartextLocalhost(applicationContext)).allowedHosts, Debug.cleartextLocalhost(applicationContext))
        val now = Instant.now()

        // Sessions nobody waits for: left by a crash or by the self-update.
        val waiting = store.state().apps.filter { it.state == "waiting-constraints" || it.state == "user-action" || it.state == "installing" }.mapNotNull { it.sessionId }.toSet()
        installer.abandonStale(waiting)

        val due = Decide.all(config, lock, packages.abi, packages, packages.self).filterIsInstance<AppState.Behind>().filter { s ->
            val r = store.app(s.pkg)
            r == null || (r.state == "behind" && Backoff.due(r.nextTry?.let { t -> runCatching { Instant.parse(t) }.getOrNull() }, now))
        }
        for ((i, s) in due.withIndex()) {
            setForeground(Notifications.foregroundInfo(context, "Updating ${s.entry.label.ifEmpty { s.pkg }} (${i + 1}/${due.size})"))
            one(context, store, config, s, lockSha, fetcher, installer, packages)
        }
        // Sessions the system is holding for a quiet moment report through
        // the receiver; a delayed check reads the package manager regardless.
        if (store.state().apps.any { it.state == "waiting-constraints" }) Schedule.reconcile(context, RECONCILE_AFTER_MINUTES)
        Result.success()
    }

    private suspend fun one(context: Context, store: Store, config: Config, s: AppState.Behind, lockSha: String?, fetcher: Fetcher, installer: SessionInstaller, packages: AndroidPackages) {
        val entry = s.entry
        val pin = config.pinFor(entry.pkg) ?: return
        val label = config.managed.firstOrNull { it.pkg == entry.pkg }?.label ?: entry.pkg
        val prev = store.app(entry.pkg) ?: Store.AppRecord(pkg = entry.pkg, label = label, state = "behind", since = Instant.now().toString())
        val boundReached = prev.reason?.startsWith("bound reached") == true
        val file = store.downloadFile(context, entry.pkg)
        fun fail(why: String) {
            file.delete()
            val attempts = prev.attempts + 1
            store.setApp(prev.copy(state = "failed", lastError = why, attempts = attempts, nextTry = Backoff.next(attempts, Instant.now()).toString(), since = Instant.now().toString(), sessionId = null))
            store.record(entry.pkg, "failed", from = s.installed, to = entry.versionCode, lockSha = lockSha, detail = why)
            if (Backoff.shouldNotify(attempts)) Notifications.failure(context, entry.pkg, label, why)
        }

        store.setApp(prev.copy(state = "downloading", label = label, locked = entry.versionCode, since = Instant.now().toString(), lastError = null))
        store.record(entry.pkg, "download-started", from = s.installed, to = entry.versionCode, lockSha = lockSha)
        var download: Fetcher.Download = Fetcher.Download.Failed("no url")
        val tried = mutableListOf<String>()
        for (url in entry.urls) {
            when (val d = fetcher.download(url, file, entry.size)) {
                is Fetcher.Download.Ok -> { download = d; store.record(entry.pkg, "downloaded", to = entry.versionCode, lockSha = lockSha, detail = "$url, ${d.size} bytes") }
                is Fetcher.Download.Failed -> { download = d; tried += "$url: ${d.reason}" }
            }
            if (download is Fetcher.Download.Ok) break
        }
        val ok = download as? Fetcher.Download.Ok ?: return fail("download: ${tried.joinToString("; ")}")

        store.setApp(store.app(entry.pkg)!!.copy(state = "verifying", since = Instant.now().toString()))
        when (val out = installer.install(entry, pin, file, ok.sha256, lockSha, config.policy.constraints, boundReached)) {
            is SessionInstaller.Outcome.Refused -> fail("refused: ${out.reason}")
            is SessionInstaller.Outcome.AlreadyCurrent -> {
                file.delete()
                store.setApp(store.app(entry.pkg)!!.copy(state = "current", installed = out.versionCode, sessionId = null, since = Instant.now().toString(), reason = null))
                store.record(entry.pkg, "already-current", to = out.versionCode, lockSha = lockSha, detail = "another zone got there first")
            }
            is SessionInstaller.Outcome.Committed -> {
                file.delete()
                val state = if (out.waitingForConstraints) "waiting-constraints" else "installing"
                store.setApp(store.app(entry.pkg)!!.copy(state = state, sessionId = out.sessionId, since = Instant.now().toString(), reason = null))
                store.record(entry.pkg, "committed", from = s.installed, to = entry.versionCode, lockSha = lockSha, detail = "session ${out.sessionId}${if (out.waitingForConstraints) ", waiting for a quiet moment" else ""}")
                if (!out.waitingForConstraints) awaitResult(store, entry.pkg, out.sessionId)
            }
        }
    }

    /** The receiver writes the result; this run only waits for it, bounded. Past the bound the record stays `installing` and the next check reconciles against the package manager. */
    private suspend fun awaitResult(store: Store, pkg: String, sessionId: Int) {
        val deadline = System.currentTimeMillis() + RESULT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val r = store.app(pkg)
            if (r == null || r.sessionId != sessionId || r.state !in setOf("installing")) return
            delay(1_000)
        }
        store.record(pkg, "no-result-yet", detail = "session $sessionId gave no result within ${RESULT_WAIT_MS / 1000} s; the next check reads the package manager")
    }

    companion object {
        const val RESULT_WAIT_MS = 10 * 60 * 1000L
        const val RECONCILE_AFTER_MINUTES = 5L
    }
}
