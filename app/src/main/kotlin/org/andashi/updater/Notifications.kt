package org.andashi.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import org.andashi.updater.ui.StatusActivity

/**
 * Three channels, by what they ask of a person (design §14): action needed,
 * failure, and the low-priority "working" the foreground service must show.
 * Success is silent; the status screen has the history.
 */
object Notifications {
    const val CHANNEL_ACTION = "action"
    const val CHANNEL_FAILURE = "failure"
    const val CHANNEL_SERVICE = "service"
    const val ID_SERVICE = 1
    private const val ID_ACTION_BASE = 1000
    private const val ID_FAILURE_BASE = 2000
    const val ID_LOCK = 3000
    const val ID_EXEMPTION = 3001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ACTION, context.getString(R.string.channel_action), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FAILURE, context.getString(R.string.channel_failure), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN))
    }

    fun foregroundInfo(context: Context, text: String): ForegroundInfo {
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Andashi Updater")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setOngoing(true)
            .setContentIntent(openStatus(context))
            .build()
        return ForegroundInfo(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** The system wants a tap for [pkg]; [confirm] is the intent the package installer handed us. Never started from the background: GrapheneOS blocks that (design §7). */
    fun actionNeeded(context: Context, pkg: String, label: String, confirm: Intent?) {
        ensureChannels(context)
        val tap = if (confirm != null) {
            PendingIntent.getActivity(context, pkg.hashCode(), confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            openStatus(context)
        }
        val n = Notification.Builder(context, CHANNEL_ACTION)
            .setContentTitle("Update needs your confirmation")
            .setContentText("$label waits for a tap")
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ACTION_BASE + (pkg.hashCode() and 0xffff), n)
    }

    fun clearAction(context: Context, pkg: String) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_ACTION_BASE + (pkg.hashCode() and 0xffff))

    fun failure(context: Context, pkg: String, label: String, reason: String) {
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_FAILURE)
            .setContentTitle("Update failed: $label")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentIntent(openStatus(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_FAILURE_BASE + (pkg.hashCode() and 0xffff), n)
    }

    fun clearFailure(context: Context, pkg: String) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_FAILURE_BASE + (pkg.hashCode() and 0xffff))

    /** The lock itself is the problem: stale, unreachable for too long, refused. One notification, replaced in place. */
    fun lockProblem(context: Context, reason: String) {
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_FAILURE)
            .setContentTitle("Updates cannot be checked")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentIntent(openStatus(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_LOCK, n)
    }

    fun clearLockProblem(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_LOCK)

    /** The battery exemption is missing: its own notification, so a healthy lock check cannot clear it. */
    fun exemptionMissing(context: Context) {
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_FAILURE)
            .setContentTitle("Updates cannot run in the background")
            .setContentText("The updater is not exempt from battery optimisation; the laptop grants it.")
            .setStyle(Notification.BigTextStyle().bigText("The updater is not exempt from battery optimisation (cmd deviceidle whitelist). Nothing can be downloaded or installed in the background until the laptop grants it."))
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentIntent(openStatus(context))
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_EXEMPTION, n)
    }

    fun clearExemptionMissing(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_EXEMPTION)

    private fun openStatus(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
