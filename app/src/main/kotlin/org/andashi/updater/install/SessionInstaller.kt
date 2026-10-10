package org.andashi.updater.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import org.andashi.updater.core.Config
import org.andashi.updater.core.LockEntry
import org.andashi.updater.core.Packages
import org.andashi.updater.core.Verify
import org.andashi.updater.net.HashingOutputStream
import java.io.File

/**
 * One verified file into one install session (design §7, steps 4-5).
 *
 * The order is the order of the design: the file is checked against the
 * lock and the pin before a session exists; the bytes are hashed again
 * while they are written into the session and the session is abandoned if
 * that hash differs; the package manager is asked one last time what is
 * installed, right before commit, so another zone that got there first
 * costs an abandoned session, not a failed install.
 */
class SessionInstaller(private val context: Context, private val packages: Packages) {

    sealed class Outcome {
        /**
         * Verified and written; not yet committed. The caller persists the
         * session id first and then calls [commit], so the status receiver
         * can never run before the record it updates exists (a fast
         * callback, or a self-update replacing the process, would otherwise
         * overwrite `current` with `installing`).
         */
        class Ready(val sessionId: Int, val waitingForConstraints: Boolean, val commit: () -> Unit) : Outcome()
        data class AlreadyCurrent(val versionCode: Long) : Outcome()
        data class Ahead(val versionCode: Long) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    fun install(entry: LockEntry, pin: String, file: File, fileSha256: String, lockSha: String?, constraints: Config.InstallConstraints, boundReached: Boolean): Outcome {
        when (val v = Verify.downloaded(entry, pin, fileSha256, file.length(), packages.archive(file.absolutePath))) {
            is Verify.Result.Refused -> return Outcome.Refused(v.reason)
            Verify.Result.Ok -> {}
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(entry.pkg)
            setSize(entry.size)
            // The silent path: installer of record + UPDATE_PACKAGES_WITHOUT_USER_ACTION
            // + the install appop. When a condition does not hold, the system
            // answers STATUS_PENDING_USER_ACTION and the receiver posts the tap.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        val written: HashingOutputStream
        try {
            // fsync wants the stream the session handed out, not a wrapper
            // around it ("Unrecognized stream", measured 2026-10-10).
            val raw = session.openWrite("base.apk", 0, entry.size)
            written = HashingOutputStream(raw)
            file.inputStream().use { it.copyTo(written, 64 * 1024) }
            written.flush()
            session.fsync(raw)
            raw.close()
        } catch (e: Exception) {
            session.abandon()
            return Outcome.Refused("writing the session: ${e.javaClass.simpleName}: ${e.message}")
        }
        when (val c = Verify.beforeCommit(entry, written.sha256(), packages.installed(entry.pkg))) {
            is Verify.Commit.Refused -> { session.abandon(); return Outcome.Refused(c.reason) }
            is Verify.Commit.AlreadyCurrent -> { session.abandon(); return Outcome.AlreadyCurrent(c.versionCode) }
            is Verify.Commit.Ahead -> { session.abandon(); return Outcome.Ahead(c.versionCode) }
            Verify.Commit.Proceed -> {}
        }
        val status = InstallStatusReceiver.pendingIntent(context, sessionId, entry.pkg, entry.versionCode, lockSha)
        val gentle = !boundReached && (constraints.notInCall || constraints.notForeground)
        if (gentle) {
            // Android 14+: commit when the app is not in use. The bound is the
            // host's; when it elapses the receiver hears a timeout and the next
            // run commits directly (design §11).
            val ic = PackageInstaller.InstallConstraints.Builder().apply {
                if (constraints.notInCall) setNotInCallRequired()
                if (constraints.notForeground) { setAppNotForegroundRequired(); setAppNotInteractingRequired() }
            }.build()
            session.close()
            return Outcome.Ready(sessionId, waitingForConstraints = true) {
                installer.commitSessionAfterInstallConstraintsAreMet(sessionId, status.intentSender, ic, constraints.boundHours * 3_600_000L)
            }
        }
        return Outcome.Ready(sessionId, waitingForConstraints = false) {
            session.commit(status.intentSender)
            session.close()
        }
    }

    /** The bound elapsed: commit the session the constraints were holding, directly. */
    fun commitDirectly(sessionId: Int, pkg: String, versionCode: Long, lockSha: String?): Boolean {
        val installer = context.packageManager.packageInstaller
        val info = installer.getSessionInfo(sessionId) ?: return false
        if (info.appPackageName != pkg) return false
        return try {
            installer.openSession(sessionId).use { s ->
                s.commit(InstallStatusReceiver.pendingIntent(context, sessionId, pkg, versionCode, lockSha).intentSender)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sessions this app owns that nothing is waiting for any more - after a crash, or a self-update that restarted the process. */
    fun abandonStale(keep: Set<Int>) {
        val installer = context.packageManager.packageInstaller
        for (s in installer.mySessions) {
            if (s.sessionId !in keep && !s.isCommitted) runCatching { installer.abandonSession(s.sessionId) }
        }
    }

    companion object {
        fun confirmIntentOf(intent: Intent): Intent? =
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

        fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    }
}
