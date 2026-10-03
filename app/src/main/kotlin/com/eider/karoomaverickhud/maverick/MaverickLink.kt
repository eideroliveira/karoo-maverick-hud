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
 *  - The SDK's name-based auto-load doesn't find our key (res/raw/app.key is resource
 *    "raw/app"), which surfaces as AppErrorCode.ApiKeyMissing. So we read the bytes and
 *    call auth().setApiKey() ourselves — once, at init. Not again per sign-in: setApiKey
 *    also drops the SDK's in-memory glasses key, which is what lets a reconnect within the
 *    same process sign in without internet.
 *  - Registering/unregistering listeners around a short-lived UI aborts the secured
 *    handshake; keeping them for the whole process lets auth -> onReady complete and gives
 *    every screen one source of truth for link/ready state.
 *  - Every failure is published as a rider-facing [issue] (see [LinkIssue]), since a failed
 *    sign-in leaves the glasses dark and the Karoo is the only place to explain it.
 */
object MaverickLink {
    /** Auth finished and the secured link is up — the real "connected" signal. */
    val ready = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    /** Raw SDK error code of the last failure (for logs and the pair dialog's flow control). */
    val lastError = MutableStateFlow<String?>(null)
    /** Why the glasses can't come up, for the rider; null once they sign in. */
    val issue = MutableStateFlow<LinkIssue?>(null)
    /** Glasses serial, captured at auth; surfaced as ManufacturerInfo on the Karoo sensor page. */
    val serial = MutableStateFlow<String?>(null)

    fun install(context: Context, app: IEvsApp) {
        val apiKey = runCatching {
            // Prefer app.key if present, fallback to sdk.key
            val id = context.resources.getIdentifier("app", "raw", context.packageName).takeIf { it != 0 }
                ?: context.resources.getIdentifier("sdk", "raw", context.packageName)
            if (id != 0) context.resources.openRawResource(id).use { it.readBytes() } else null
        }.getOrNull()
        Timber.i("API key loaded: ${apiKey?.size ?: -1} bytes")
        applyApiKey(app, apiKey)
        CertificateKeeper.install(context, apiKey)

        app.registerAppEvents(object : IEvsAppEvents {
            override fun onReady() {
                Timber.i("Evs onReady")
                ready.value = true
                lastError.value = null
                issue.value = null
                // Make sure the display is actually on, and enable touch — without these the
                // glasses can stay blank and temple-pad swipes never reach the HUD.
                runCatching { Evs.instance().display().turnDisplayOn() }
                    .onFailure { Timber.w(it, "turnDisplayOn failed") }
                runCatching { Evs.instance().sensors().enableTouch(true) }
                    .onFailure { Timber.w(it, "enableTouch failed") }
                // A sign-in may have just fetched a fresh certificate; re-read it for the status UI.
                CertificateKeeper.refresh("ready")
            }

            override fun onError(code: AppErrorCode, message: String) {
                val cert = runCatching { CertificateKeeper.currentStatus() }.getOrNull()
                val online = CertificateKeeper.online.value
                Timber.w("Evs onError $code: $message (cert=$cert online=$online)")
                // issue before lastError: the pair dialog waits on lastError, then reads issue.
                issue.value = LinkIssue.fromSdkError(code.name, cert, online)
                lastError.value = code.toString()
            }

            override fun onBeforeRendering(time: Long) {}

            override fun onBeginAuth(serial: String, fwVersion: Int) {
                Timber.i("Evs onBeginAuth serial=$serial fw=$fwVersion")
                MaverickLink.serial.value = serial
                CertificateKeeper.rememberSerial(serial)
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
                if (!enabled) {
                    issue.value = LinkIssue.BLUETOOTH_OFF
                } else if (issue.value == LinkIssue.BLUETOOTH_OFF) {
                    issue.value = null
                }
            }
        })
    }

    private fun applyApiKey(app: IEvsApp, key: ByteArray?) {
        if (key == null) {
            Timber.w("No app.key/sdk.key bytes to apply")
            issue.value = LinkIssue.NO_KEY
            return
        }
        runCatching {
            val ok = app.auth().setApiKey(key)
            Timber.i("setApiKey(${key.size} bytes) ok=$ok")
        }.onFailure { Timber.w(it, "setApiKey failed") }
    }
}
