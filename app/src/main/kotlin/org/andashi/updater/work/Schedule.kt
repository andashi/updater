package org.andashi.updater.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.andashi.updater.core.Config
import java.util.concurrent.TimeUnit

/**
 * When the updater runs (design §8). The periodic check is WorkManager's;
 * the interval and constraints come from the host's config. A check that
 * finds work enqueues one install run, unique, so two triggers never
 * install the same package twice at once.
 */
object Schedule {
    const val CHECK_PERIODIC = "check-periodic"
    const val CHECK_NOW = "check-now"
    const val INSTALL = "install"
    const val RECONCILE = "reconcile"
    const val KEY_TRIGGER = "trigger"

    fun apply(context: Context, config: Config) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.schedule.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(config.schedule.requireCharging)
            .build()
        // Hours as the host wrote them, never below WorkManager's floor.
        val hours = config.schedule.checkEveryHours.toLong().coerceAtLeast(1)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CHECK_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CheckWorker>(hours, TimeUnit.HOURS)
                .setConstraints(if (config.hostOnly) Constraints.NONE else constraints)
                .setInputData(workDataOf(KEY_TRIGGER to "periodic"))
                .build(),
        )
    }

    /** One check now, replacing one still waiting (a waiting one would queue this behind an unsatisfiable constraint; measured 2026-10-09). */
    fun checkNow(context: Context, trigger: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TRIGGER to trigger))
                .build(),
        )
    }

    /**
     * A check after a delay, for sessions the system holds until a quiet
     * moment (install constraints): their result arrives at the receiver,
     * but a receiver can be lost with its process, and a state that says
     * "waiting" for a package the package manager already updated is the
     * proxy the design forbids. REPLACE: one pending reconcile at a time.
     */
    fun reconcile(context: Context, delayMinutes: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECONCILE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to "reconcile"))
                .build(),
        )
    }

    /** The install run: KEEP, because one that is already running is doing exactly this. The check that found new work re-enqueues after it. */
    fun install(context: Context, trigger: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            INSTALL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<InstallWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TRIGGER to trigger))
                .build(),
        )
    }
}
