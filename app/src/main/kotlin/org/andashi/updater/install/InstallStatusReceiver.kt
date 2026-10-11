package org.andashi.updater.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.andashi.updater.Notifications
import org.andashi.updater.UpdaterApp
import org.andashi.updater.core.AppState
import org.andashi.updater.core.Backoff
import org.andashi.updater.core.Decide
import org.andashi.updater.pm.AndroidPackages
import org.andashi.updater.store.Store
import org.andashi.updater.work.Loader
import org.andashi.updater.work.Records
import org.andashi.updater.work.Schedule
import java.time.Instant

/**
 * The package installer's answer to a session, and the only place that may
 * move a package to `current` (design §3.3): not on commit, not on
 * STATUS_SUCCESS alone, but on STATUS_SUCCESS followed by the package
 * manager reporting the lock's versionCode.
 *
 * This receiver may be the first code to run in a process the self-update
 * just replaced; it reads everything it needs from the intent and the
 * store, never from memory.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Off the main thread: confirming success reads the package manager
        // and, after a cache miss, hashes the installed APK.
        val pending = goAsync()
        Thread {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val store = UpdaterApp.store(context)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val expected = intent.getLongExtra(EXTRA_VERSION, -1)
        val lockSha = intent.getStringExtra(EXTRA_LOCK)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, intent.getIntExtra(EXTRA_SESSION, -1))
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val record = store.app(pkg) ?: Store.AppRecord(pkg = pkg, state = "installing", since = Instant.now().toString())
        val now = Instant.now().toString()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // A condition of the silent path does not hold. The person
                // decides; the notification carries the system's own intent.
                val confirm = SessionInstaller.confirmIntentOf(intent)
                store.setApp(record.copy(state = "user-action", sessionId = sessionId, since = now, lastError = null))
                store.record(pkg, "user-action", to = expected, lockSha = lockSha, detail = "the system asks for confirmation")
                Notifications.actionNeeded(context, pkg, record.label.ifEmpty { pkg }, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Success is the full decision's to confirm (design §7): the
                // lock's version and bytes, the pinned signer, and ownership -
                // never the version number alone.
                val packages = AndroidPackages(context)
                val installed = packages.installed(pkg)
                val config = Loader.config(context, store)
                val managed = config?.managed?.firstOrNull { it.pkg == pkg }
                val decided = if (config != null && managed != null) Decide.state(managed, Loader.lock(context, store, config), packages.abi, installed, packages.self) else null
                if (decided is AppState.Current && installed != null && installed.versionCode == expected) {
                    store.setApp(record.copy(state = "current", installed = installed.versionCode, locked = expected, installer = installed.installer, sessionId = null, attempts = 0, nextTry = null, since = now, lastError = null, reason = null))
                    store.record(pkg, "updated", from = record.installed, to = installed.versionCode, lockSha = lockSha, detail = "confirmed by the package manager")
                    Notifications.clearAction(context, pkg)
                    Notifications.clearFailure(context, pkg)
                } else if (decided != null) {
                    // Installed, but not current by the full decision: say
                    // what it is (not-owner, behind as a rebuild, ahead, ...).
                    val (name, reason) = Records.fromDecision(decided, packages.self)
                    val why = "success reported; the package manager shows ${installed?.versionCode ?: "nothing"}, decided $name${reason?.let { ": $it" } ?: ""}"
                    store.setApp(record.copy(state = name, installed = installed?.versionCode, locked = expected, installer = installed?.installer, sessionId = null, since = now, lastError = null, reason = reason))
                    store.record(pkg, "installed-not-current", from = record.installed, to = installed?.versionCode, lockSha = lockSha, detail = why)
                } else {
                    // The system said success and nothing can confirm it: the
                    // one case that must never be called done.
                    val why = "success reported, but the package manager shows ${installed?.versionCode ?: "nothing"} instead of $expected and no config or lock can decide"
                    store.setApp(record.copy(state = "failed", sessionId = null, since = now, lastError = why, attempts = record.attempts + 1, nextTry = Backoff.next(record.attempts + 1, Instant.now()).toString()))
                    store.record(pkg, "failed", to = expected, lockSha = lockSha, detail = why)
                    Notifications.failure(context, pkg, record.label.ifEmpty { pkg }, why)
                }
                Schedule.checkNow(context, "after-install")
            }
            else -> {
                val why = "${statusName(status)}: ${message ?: "no message"}"
                val timedOut = status == PackageInstaller.STATUS_FAILURE_TIMEOUT
                if (timedOut && record.state == "waiting-constraints") {
                    // The bound elapsed while waiting for a quiet moment: an
                    // update that waits forever is an unpatched app (design
                    // §11). The next install run downloads and commits a
                    // fresh session without constraints ("bound reached").
                    store.setApp(record.copy(state = "behind", sessionId = null, since = now, lastError = null, reason = "bound reached, installing without waiting"))
                    store.record(pkg, "bound-reached", to = expected, lockSha = lockSha, detail = why)
                    Schedule.install(context, "bound-reached")
                } else {
                    val attempts = record.attempts + 1
                    store.setApp(record.copy(state = "failed", sessionId = null, since = now, lastError = why, attempts = attempts, nextTry = Backoff.next(attempts, Instant.now()).toString()))
                    store.record(pkg, "failed", to = expected, lockSha = lockSha, detail = why)
                    if (Backoff.shouldNotify(attempts)) Notifications.failure(context, pkg, record.label.ifEmpty { pkg }, why)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "org.andashi.updater.pkg"
        const val EXTRA_VERSION = "org.andashi.updater.versionCode"
        const val EXTRA_LOCK = "org.andashi.updater.lock"
        const val EXTRA_SESSION = "org.andashi.updater.session"

        fun pendingIntent(context: Context, sessionId: Int, pkg: String, versionCode: Long, lockSha: String?): PendingIntent {
            val intent = Intent(context, InstallStatusReceiver::class.java)
                .setAction("org.andashi.updater.action.INSTALL_STATUS")
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_VERSION, versionCode)
                .putExtra(EXTRA_LOCK, lockSha)
                .putExtra(EXTRA_SESSION, sessionId)
            // Mutable: the package installer fills in the status extras.
            return PendingIntent.getBroadcast(context, sessionId, intent, SessionInstaller.pendingFlags())
        }

        fun statusName(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE -> "failure"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "timeout"
            else -> "status $status"
        }
    }
}
