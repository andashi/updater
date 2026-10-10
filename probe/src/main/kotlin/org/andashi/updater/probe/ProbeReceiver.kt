package org.andashi.updater.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The shell's handle on the probe. Three actions:
 *
 * - `RUN`: one expedited run now (the shape an update install will have).
 * - `SCHEDULE`: a periodic run every 15 minutes - WorkManager's minimum -
 *   for a soak over hours, with the device left alone. This is the run
 *   that answers the design's question, because a run the shell just
 *   triggered has the device awake and the screen possibly on.
 * - `CANCEL`: stop the periodic run and clear the record.
 *
 * The gate is the manifest alone: `android:permission=WRITE_SECURE_SETTINGS`
 * on the receiver, which only shell and system hold. A uid check like the
 * provider's is NOT possible here and must not be added: a broadcast is
 * delivered by the activity manager, so inside onReceive
 * Binder.getCallingUid() is the app's own uid, never the sender's. Measured
 * 2026-10-09 on emulator-5562 (shell): a check copied from the provider
 * crashed the receiver with "caller uid 10201", the probe's own. This is
 * also why the launcher's reload receiver carries only the manifest
 * permission.
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: ProbeWorker.DEFAULT_URL
        // `constraint=none` drops the network constraint. For the emulator
        // only: its Wi-Fi reports PARTIAL_CONNECTIVITY and is never VALIDATED
        // (measured 2026-10-09 on emulator-5562), so WorkManager's CONNECTED
        // never holds there and a run would wait forever. The soak on a
        // phone keeps CONNECTED, which is what the updater will ask for.
        val constraints = if (intent.getStringExtra("constraint") == "none") Constraints.NONE else networkConstraints()
        val wm = WorkManager.getInstance(context)
        when (intent.action) {
            // REPLACE, not APPEND: a run that waits on an unsatisfiable
            // constraint would otherwise queue every later run behind it
            // (seen 2026-10-09 on the emulator, where CONNECTED never holds).
            ACTION_RUN -> wm.enqueueUniqueWork(
                "probe-run",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ProbeWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(ProbeWorker.KEY_URL to url, ProbeWorker.KEY_TRIGGER to "shell"))
                    .build(),
            )
            ACTION_SCHEDULE -> wm.enqueueUniquePeriodicWork(
                "probe-periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ProbeWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(networkConstraints())
                    .setInputData(workDataOf(ProbeWorker.KEY_URL to url, ProbeWorker.KEY_TRIGGER to "periodic"))
                    .build(),
            )
            ACTION_CANCEL -> {
                // Cancellation is asynchronous; a worker still running would
                // append its record after a clear. So: finish the broadcast
                // off the main thread, wait for both cancellations to be
                // processed, then clear.
                val pending = goAsync()
                Thread {
                    try {
                        wm.cancelUniqueWork("probe-periodic").result.get()
                        wm.cancelUniqueWork("probe-run").result.get()
                        ProbeStore.clear(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    // CONNECTED is what the updater will ask for too. In a lockdown zone the
    // constraint can hold while the VPN is down (design §8); the record's
    // `network` field says which it was.
    private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val ACTION_RUN = "org.andashi.updater.probe.RUN"
        const val ACTION_SCHEDULE = "org.andashi.updater.probe.SCHEDULE"
        const val ACTION_CANCEL = "org.andashi.updater.probe.CANCEL"
    }
}
