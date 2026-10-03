package com.eider.karoomaverickhud

import android.app.Application
import com.eider.karoomaverickhud.maverick.LinkIssue
import com.eider.karoomaverickhud.maverick.MaverickLink
import com.everysight.evskit.android.Evs
import timber.log.Timber

class KHudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // Per Maverick docs: "Evs.init(context).start()" is the canonical lifecycle.
        // Register app/comm listeners once, before connecting, so the secured handshake is never
        // interrupted by UI. A failure here would otherwise crash every extension start; keep the
        // process up so the Karoo can say what's wrong (see MaverickLink.issue).
        runCatching {
            val evs = Evs.init(this)
            MaverickLink.install(this, evs)
            evs.start()
        }.onFailure {
            Timber.e(it, "Maverick SDK init failed")
            MaverickLink.issue.value = LinkIssue.SDK_FAILED
        }
    }
}
