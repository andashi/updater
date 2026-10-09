package org.andashi.updater.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.andashi.updater.UpdaterApp
import org.andashi.updater.Debug
import org.andashi.updater.core.Config

/**
 * BOOT_COMPLETED reaches a secondary user's apps when that user starts, not
 * only when the device boots (measured 2026-10-02 on `am start-user`). So a
 * zone that was stopped catches up the moment it runs again (design §8),
 * and the periodic schedule is re-asserted from the config it holds.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val text = UpdaterApp.store(context).configText() ?: return
        val config = runCatching { Config.parse(text, Debug.cleartextLocalhost(context)) }.getOrNull() ?: return
        Schedule.apply(context, config)
        Schedule.checkNow(context, "zone-start")
    }
}
