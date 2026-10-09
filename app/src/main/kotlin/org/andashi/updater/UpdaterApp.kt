package org.andashi.updater

import android.app.Application
import org.andashi.updater.store.Store

/** One Store per process; WorkManager initialises itself through androidx.startup. */
class UpdaterApp : Application() {
    val store: Store by lazy { Store(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    companion object {
        fun store(context: android.content.Context): Store = (context.applicationContext as UpdaterApp).store
    }
}
