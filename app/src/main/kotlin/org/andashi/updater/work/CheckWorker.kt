package org.andashi.updater.work

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.andashi.updater.Notifications
import org.andashi.updater.UpdaterApp
import org.andashi.updater.core.AppState
import org.andashi.updater.core.Backoff
import org.andashi.updater.Debug
import org.andashi.updater.core.Config
import org.andashi.updater.core.Decide
import org.andashi.updater.core.Fresh
import org.andashi.updater.core.Freshness
import org.andashi.updater.core.Heartbeat
import org.andashi.updater.core.Hex
import org.andashi.updater.core.Lock
import org.andashi.updater.core.LockFormatException
import org.andashi.updater.net.Fetcher
import org.andashi.updater.net.HttpFetcher
import org.andashi.updater.pm.AndroidPackages
import org.andashi.updater.store.Store
import java.security.MessageDigest
import java.time.Instant

/**
 * One check (design §7, steps 1-2): get the lock, decide every managed
 * package's state, write the state the host reads, and enqueue the install
 * run if anything is due. Cheap and frequent; the install run is the
 * expensive one and runs only when this one found work.
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = UpdaterApp.store(context)
        val trigger = inputData.getString(Schedule.KEY_TRIGGER) ?: "unknown"
        val now = Instant.now()
        val packages = AndroidPackages(context)
        val self = packages.installed(packages.self)

        val config = store.configText()?.let { runCatching { Config.parse(it, Debug.cleartextLocalhost(applicationContext)) }.getOrNull() }
        if (config == null) {
            store.update { it.copy(lastCheck = now.toString(), lastCheckError = "no configuration from the host", updaterVersionCode = self?.versionCode ?: 0, updaterInstaller = self?.installer) }
            return Result.success()
        }

        // The exemption that lets the install run start its foreground
        // service from a background job (design §8). Granted by the host,
        // reported here, loudly when missing.
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        if (!exempt) Notifications.lockProblem(context, "The updater is not exempt from battery optimisation; the host must grant it (cmd deviceidle whitelist). Nothing can be installed in the background until then.")

        val fetcher = HttpFetcher(config.lockPolicy(Debug.cleartextLocalhost(applicationContext)).allowedHosts, Debug.cleartextLocalhost(applicationContext))
        val (lock, lockState) = obtainLock(config, store, fetcher, now)

        val states = Decide.all(config, lock, packages.abi, packages, packages.self)
        val lockSha = lockState.sha256
        val previous = store.state().apps.associateBy { it.pkg }
        val records = states.map { toRecord(it, config, previous[it.pkg], now, lockSha, packages) }

        val foreign = Decide.foreign(config, packages).map { Store.ForeignApp(it.pkg, it.installer, it.versionCode) }
        val violations = Decide.netFalseViolations(config, packages)
        val checks = Store.ChecksState(netFalseOk = config.checks.netFalse - violations.toSet(), netFalseViolations = violations)

        store.update {
            it.copy(
                updaterVersionCode = self?.versionCode ?: 0, updaterInstaller = self?.installer,
                exemption = if (exempt) "granted" else "missing",
                lock = lockState, lastCheck = now.toString(),
                lastCheckOk = if (lockState.lastError == null) now.toString() else it.lastCheckOk,
                lastCheckError = lockState.lastError,
                apps = records, foreign = foreign, checks = checks,
            )
        }
        for ((state, record) in states.zip(records)) notify(context, state, record, previous[record.pkg])

        val due = records.any { it.state == "behind" && Backoff.due(it.nextTry?.let { t -> runCatching { Instant.parse(t) }.getOrNull() }, now) }
        if (due && exempt) Schedule.install(context, trigger)
        // While the system holds a session for a quiet moment, keep looking
        // at the package manager at intervals, whatever the receiver does.
        if (records.any { it.state == "waiting-constraints" }) Schedule.reconcile(context, RECONCILE_EVERY_MINUTES)
        return Result.success()
    }

    /** The lock from the host (host-only) or from the network, with the cached one as the fallback and the rollback guard. */
    private fun obtainLock(config: Config, store: Store, fetcher: Fetcher, now: Instant): Pair<Lock?, Store.LockState> {
        val cachedText = if (config.hostOnly) store.hostLockText() else store.lockText()
        val cached = cachedText?.let { runCatching { Lock.parse(it, config.lockPolicy(Debug.cleartextLocalhost(applicationContext))) }.getOrNull() }
        val cachedSha = cachedText?.let { sha(it) }
        val prior = store.state().lock

        if (config.hostOnly) {
            val state = Store.LockState(generated = cached?.generated, sha256 = cachedSha, fetchedAt = prior.fetchedAt, source = "host",
                freshness = "unknown", freshnessReason = "host-only: the lock is as fresh as the last push",
                lastError = if (cached == null) "no lock from the host yet" else null)
            return cached to state
        }

        var text = cachedText; var lock = cached; var fetchedAt = prior.fetchedAt; var error: String? = null
        when (val r = fetcher.text(config.lock.url, store.lockEtag(), MAX_LOCK_BYTES)) {
            is Fetcher.TextResult.Ok -> try {
                lock = Lock.parse(r.text, config.lockPolicy(Debug.cleartextLocalhost(applicationContext)), cached)
                text = r.text
                store.writeLock(r.text, r.etag)
                fetchedAt = now.toString()
                if (cachedSha != sha(r.text)) store.record("-", "lock-fetched", lockSha = sha(r.text), detail = "generated ${lock.generated}")
            } catch (e: LockFormatException) {
                error = "lock refused: ${e.message}"
                store.record("-", "lock-refused", detail = e.message)
            }
            Fetcher.TextResult.NotModified -> fetchedAt = now.toString()
            is Fetcher.TextResult.Failed -> error = "lock unreachable: ${r.reason}"
        }
        val lockSha = text?.let { sha(it) }
        val heartbeat = if (config.lock.heartbeatUrl.isNotEmpty()) {
            (fetcher.text(config.lock.heartbeatUrl, null, MAX_LOCK_BYTES) as? Fetcher.TextResult.Ok)?.let { Heartbeat.parse(it.text) }
        } else null
        val freshness = if (lockSha != null) Fresh.of(lockSha, lock?.generatedAt, heartbeat, now, config.lock.maxAgeDays) else Freshness.Unknown("no lock")
        val (fresh, why) = when (freshness) {
            is Freshness.Fresh -> "fresh" to "refresh ran ${freshness.checkedAt}"
            is Freshness.Stale -> "stale" to freshness.reason
            is Freshness.Unknown -> "unknown" to freshness.reason
        }
        if (freshness is Freshness.Stale) Notifications.lockProblem(applicationContext, "The lock is stale: ${freshness.reason}.")
        else if (error != null && lock == null) Notifications.lockProblem(applicationContext, error)
        else if (error == null) Notifications.clearLockProblem(applicationContext)
        return lock to Store.LockState(generated = lock?.generated, sha256 = lockSha, fetchedAt = fetchedAt, source = "fetch", freshness = fresh, freshnessReason = why, lastError = error)
    }

    /**
     * The record the host reads, carrying over what is in flight. A package
     * the install run is working on keeps its state until the receiver or
     * the package manager says otherwise; a decision that now says
     * `current` wins over any in-flight state, because it is the package
     * manager speaking.
     */
    private fun toRecord(state: AppState, config: Config, prev: Store.AppRecord?, now: Instant, lockSha: String?, packages: AndroidPackages): Store.AppRecord {
        val label = config.managed.firstOrNull { it.pkg == state.pkg }?.label ?: state.pkg
        val installed = packages.installed(state.pkg)
        val base = Store.AppRecord(pkg = state.pkg, label = label, state = "", installed = installed?.versionCode, installer = installed?.installer, since = prev?.since ?: now.toString())
        fun keepSince(newState: String) = if (prev?.state == newState) prev.since else now.toString()
        return when (state) {
            is AppState.Current -> {
                if (prev != null && prev.state != "current") store.record(state.pkg, "confirmed-current", from = prev.installed, to = state.versionCode, lockSha = lockSha)
                base.copy(state = "current", locked = state.versionCode, since = keepSince("current"))
            }
            is AppState.Behind -> when (prev?.state) {
                "downloading", "verifying", "installing", "waiting-constraints", "user-action" ->
                    // In flight only while the session the record names still
                    // exists; a session the system dropped (reboot, abandon,
                    // timeout the receiver never heard) goes back to behind.
                    if (prev.sessionId != null && packages.sessionExists(prev.sessionId)) {
                        prev.copy(installed = installed?.versionCode, locked = state.entry.versionCode, installer = installed?.installer)
                    } else if (prev.state == "downloading" || prev.state == "verifying") {
                        prev.copy(installed = installed?.versionCode, locked = state.entry.versionCode, installer = installed?.installer)
                    } else {
                        store.record(state.pkg, "session-lost", to = state.entry.versionCode, lockSha = lockSha, detail = "session ${prev.sessionId} is gone; trying again")
                        base.copy(state = "behind", locked = state.entry.versionCode)
                    }
                "failed" -> if (prev.locked == state.entry.versionCode) prev.copy(state = "behind", installer = installed?.installer) else base.copy(state = "behind", locked = state.entry.versionCode)
                else -> base.copy(state = "behind", locked = state.entry.versionCode, since = keepSince("behind"))
            }
            is AppState.Ahead -> base.copy(state = "ahead", locked = state.locked, reason = "installed ${state.installed} is newer than the lock's ${state.locked}", since = keepSince("ahead"))
            is AppState.NotOwner -> base.copy(state = "not-owner", locked = state.locked, reason = "installer of record is ${state.installer ?: "nobody"}; the host repairs this with a same-version reinstall naming the updater", since = keepSince("not-owner"))
            is AppState.Missing -> base.copy(state = "missing", reason = "not installed in this user; the updater never first-installs", since = keepSince("missing"))
            is AppState.Unverifiable -> base.copy(state = "unverifiable", reason = state.reason, since = keepSince("unverifiable"))
        }
    }

    private fun notify(context: Context, state: AppState, record: Store.AppRecord, prev: Store.AppRecord?) {
        when (state) {
            is AppState.Unverifiable -> if (prev?.state != "unverifiable" || prev.reason != record.reason) Notifications.failure(context, record.pkg, record.label, record.reason ?: "unverifiable")
            is AppState.Current -> Notifications.clearFailure(context, record.pkg)
            else -> {}
        }
    }

    private val store get() = UpdaterApp.store(applicationContext)

    private fun sha(text: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    companion object {
        const val MAX_LOCK_BYTES = 4L * 1024 * 1024
        const val RECONCILE_EVERY_MINUTES = 30L
    }
}
