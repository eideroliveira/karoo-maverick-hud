package com.eider.karoomaverickhud.maverick

import UIKit.app.interfaces.IEvsApp
import UIKit.services.AppErrorCode
import UIKit.services.IEvsAppEvents
import UIKit.services.IEvsCommunicationEvents
import android.content.Context
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * Persistent owner of the EvsKit app/comm event listeners and the SDK API key, installed
 * once at app start.
 *
 *  - The SDK's name-based auto-load doesn't find our key (res/raw/sdk.key is resource
 *    "raw/sdk"), which surfaces as AppErrorCode.ApiKeyMissing. So we read the bytes and
 *    call auth().setApiKey() ourselves — at init and again when auth begins.
 *  - Registering/unregistering listeners around a short-lived UI aborts the secured
 *    handshake; keeping them for the whole process lets auth -> onReady complete and gives
 *    every screen one source of truth for link/ready state.
 */
object MaverickLink {
    /** Auth finished and the secured link is up — the real "connected" signal. */
    val ready = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    @Volatile private var apiKey: ByteArray? = null

    fun install(context: Context, app: IEvsApp) {
        apiKey = runCatching {
            val id = context.resources.getIdentifier("sdk", "raw", context.packageName)
            if (id != 0) context.resources.openRawResource(id).use { it.readBytes() } else null
        }.getOrNull()
        Timber.i("sdk.key loaded: ${apiKey?.size ?: -1} bytes")
        applyApiKey(app)

        app.registerAppEvents(object : IEvsAppEvents {
            override fun onReady() {
                Timber.i("Evs onReady")
                ready.value = true
                lastError.value = null
            }

            override fun onError(code: AppErrorCode, message: String) {
                Timber.w("Evs onError $code: $message")
                lastError.value = code.toString()
            }

            override fun onBeforeRendering(time: Long) {}

            override fun onBeginAuth(serial: String, fwVersion: Int) {
                Timber.i("Evs onBeginAuth serial=$serial fw=$fwVersion")
                applyApiKey(Evs.instance()) // make sure the key is set exactly when auth starts
            }
        })

        app.comm().registerCommunicationEvents(object : IEvsCommunicationEvents {
            override fun onConnecting() { Timber.i("Evs onConnecting") }
            override fun onConnected() { Timber.i("Evs onConnected"); connected.value = true }
            override fun onDisconnected() {
                Timber.i("Evs onDisconnected")
                connected.value = false
                ready.value = false
            }
            override fun onFailedToConnect() { Timber.i("Evs onFailedToConnect") }
            override fun onAdapterStateChanged(enabled: Boolean) {
                Timber.i("Evs onAdapterStateChanged $enabled")
            }
        })
    }

    private fun applyApiKey(app: IEvsApp) {
        val key = apiKey ?: run { Timber.w("No sdk.key bytes to apply"); return }
        runCatching {
            val ok = app.auth().setApiKey(key)
            Timber.i("setApiKey(${key.size} bytes) ok=$ok")
        }.onFailure { Timber.w(it, "setApiKey failed") }
    }
}
