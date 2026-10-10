package org.andashi.updater.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.andashi.updater.Debug
import org.andashi.updater.core.Config
import org.andashi.updater.core.Lock
import org.andashi.updater.store.Store

/** The documents on disk, parsed the same way by everyone who reads them. */
object Loader {
    fun config(context: Context, store: Store): Config? =
        store.configText()?.let { runCatching { Config.parse(it, Debug.cleartextLocalhost(context)) }.getOrNull() }

    /** The lock in force: the host's in host-only mode, else the cached fetched one. */
    fun lock(context: Context, store: Store, config: Config): Lock? {
        val text = (if (config.hostOnly) store.hostLockText() else store.lockText()) ?: return null
        return runCatching { Lock.parse(text, config.lockPolicy(Debug.cleartextLocalhost(context))) }.getOrNull()
    }

    /** Whether the install run is executing right now; a killed run leaves no session behind, and its records must not stay in flight. */
    fun installRunning(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(Schedule.INSTALL).get().any { it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)
}
