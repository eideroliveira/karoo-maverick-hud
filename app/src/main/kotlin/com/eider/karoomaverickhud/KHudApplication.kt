package com.eider.karoomaverickhud

import android.app.Application
import com.everysight.evskit.android.Evs
import timber.log.Timber

class KHudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // The Maverick SDK auto-loads sdk.key from res/raw on init.
        // Per Maverick docs: "Evs.init(context).start()" is the canonical lifecycle.
        Evs.init(this).start()
    }
}
