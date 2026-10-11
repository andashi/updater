package org.andashi.updater.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.andashi.updater.work.Schedule

/**
 * `am broadcast --user N -n org.andashi.updater/.host.ControlReceiver -a org.andashi.updater.action.CHECK_NOW`
 *
 * Gated by the manifest permission alone: inside onReceive the calling uid
 * is the app's own, never the sender's (measured 2026-10-09). There is no
 * "install this" action on purpose: what to install is decided by the lock
 * and the policy, never by a command (design §9.3).
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "${context.packageName}.action.CHECK_NOW") {
            Schedule.checkNow(context, "shell")
        }
    }
}
