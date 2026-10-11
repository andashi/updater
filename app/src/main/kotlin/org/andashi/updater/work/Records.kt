package org.andashi.updater.work

import org.andashi.updater.core.AppState

/** Words for the host and the person, derived from a decision and nothing else. */
object Records {
    /** Which ownership fact is wrong, so the host repairs that one: the installer of record (a same-version reinstall naming the updater) or Android 14's update owner. */
    fun notOwnerReason(s: AppState.NotOwner, self: String): String {
        val parts = mutableListOf<String>()
        if (s.installer != self) parts += "installer of record is ${s.installer ?: "nobody"}; the host repairs this with a same-version reinstall naming the updater"
        if (s.updateOwner != null && s.updateOwner != self) parts += "update ownership is held by ${s.updateOwner}; the host repairs this with a reinstall naming the updater with --update-ownership"
        return parts.joinToString("; ")
    }

    /**
     * The state name and reason a decision maps to, for the two places that
     * must not call a package `current` on a version number alone: the
     * status receiver after STATUS_SUCCESS, and the install run's
     * already-current path. `current` only when the full decision says so.
     */
    fun fromDecision(s: AppState, self: String): Pair<String, String?> = when (s) {
        is AppState.Current -> "current" to null
        is AppState.Behind -> "behind" to if (s.rebuilt) "same version, other bytes in the lock: converging to the locked build" else null
        is AppState.Ahead -> "ahead" to "installed ${s.installed} is newer than the lock's ${s.locked}"
        is AppState.NotOwner -> "not-owner" to notOwnerReason(s, self)
        is AppState.Missing -> "missing" to "not installed in this user; the updater never first-installs"
        is AppState.Unverifiable -> "unverifiable" to s.reason
    }
}
