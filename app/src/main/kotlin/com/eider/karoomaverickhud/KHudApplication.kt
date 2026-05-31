package com.eider.karoomaverickhud

import android.app.Application
import com.eider.karoomaverickhud.maverick.MaverickLink
import com.everysight.evskit.android.Evs
import timber.log.Timber

class KHudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // The Maverick SDK auto-loads sdk.key from res/raw on init.
        // Per Maverick docs: "Evs.init(context).start()" is the canonical lifecycle.
        val evs = Evs.init(this)
        // Register app/comm listeners once, before connecting, so the secured handshake
        // (auth via the Everysight server, needs INTERNET) is never interrupted by UI.
        MaverickLink.install(this, evs)
        evs.start()
    }
}
