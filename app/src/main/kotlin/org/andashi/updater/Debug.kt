package org.andashi.updater

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * The one difference between a debug and a release build that the code
 * can see: whether `http://localhost` is an acceptable lock or APK source
 * (core's Urls). Read from the installed app's debuggable flag, so there is
 * no BuildConfig to embed and no way for a release build to carry it.
 */
object Debug {
    fun cleartextLocalhost(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
