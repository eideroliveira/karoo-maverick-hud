package com.eider.karoomaverickhud.maverick

import UIKit.app.data.TouchDirection
import android.content.Context
import com.eider.karoomaverickhud.extension.HudSnapshot
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.HudPreferences
import com.eider.karoomaverickhud.settings.PageMode
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Process-wide mirror of the glasses link state, so the ride data field
 * ([com.eider.karoomaverickhud.extension.GlassesDataType]) can reflect it without
 * being wired to a [MaverickBridge] instance (the bridge is owned by the extension
 * service and built lazily). [MaverickBridge] is the sole writer.
 */
object GlassesLinkState {
    val connected = MutableStateFlow(value = false)
}

/**
 * Owns the Maverick connection lifecycle and the single mounted [HudScreen].
 *
 *  - SDK init/start happens in [com.eider.karoomaverickhud.KHudApplication].
 *  - One [HudScreen] is mounted when the user has a paired device and the link
 *    is up; we re-mount after reconnect.
 *  - Snapshots are pushed via [update]; the screen reads them off its volatile
 *    field in `onUpdateUI`, so the writer never blocks the render thread.
 *  - Auto-cycle stamps a page index into each snapshot before forwarding.
 */
class MaverickBridge(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(value = false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val configState: StateFlow<HudConfig> =
        HudPreferences.flow(context).stateIn(scope, SharingStarted.Eagerly, HudConfig.DEFAULT)

    private val hudScreen = HudScreen()
    private var screenMounted = false

    @Volatile private var pageIndex: Int = 0
    @Volatile private var lastSnapshot: HudSnapshot = HudSnapshot.empty

    // Latest glasses battery %, refreshed by the connection loop; null when disconnected.
    @Volatile private var glassesBattery: Int? = null

    private var connectionJob: Job? = null

    /**
     * Start the extension's glasses work: auto-connect to the paired Maverick and hold the
     * link (reconnecting on drops), plus page cycling. The HUD itself shows whether a ride
     * is active; the link is no longer ride-gated. Released in [shutdown].
     */
    fun start() {
        hudScreen.onTouchPad = ::handleTouch
        startConnectionLoop()
        startPageCycler()
    }

    fun update(snapshot: HudSnapshot) {
        // Clamp in case the layout shrank (e.g. switched to a Karoo page with fewer fields).
        if (snapshot.pages.isNotEmpty() && (pageIndex >= snapshot.pages.size)) pageIndex = 0
        val stamped = snapshot.copy(pageIndex = pageIndex, battery = glassesBattery)
        lastSnapshot = stamped
        hudScreen.apply(stamped)
        mountScreenIfNeeded()
    }

    /** MANUAL mode: a temple-pad touch flips the page. Ignored in AUTO/FOLLOW_KAROO. */
    private fun handleTouch(direction: TouchDirection) {
        if (configState.value.pageMode != PageMode.MANUAL) return
        val count = lastSnapshot.pages.size
        if (count <= 1) return
        pageIndex = when (direction) {
            TouchDirection.backward -> (pageIndex - 1 + count) % count
            else -> (pageIndex + 1) % count // forward / tap / longTap -> next
        }
        hudScreen.apply(lastSnapshot.copy(pageIndex = pageIndex))
    }

    fun shutdown() {
        connectionJob?.cancel()
        connectionJob = null
        runCatching { Evs.instance().comm().disconnect() }
            .onFailure { Timber.w(it, "Evs disconnect failed") }
    }

    /**
     * Keep the paired glasses connected. Polls the SDK link state, mirrors it for the UI,
     * and re-issues a secured connect whenever the device is paired but not connected (and
     * not already connecting). Runs for the extension's whole lifetime.
     */
    private fun startConnectionLoop() {
        connectionJob = scope.launch {
            while (isActive) {
                runCatching {
                    val comm = Evs.instance().comm()
                    val connected = comm.isConnected()
                    if (connected != _connectionState.value) {
                        _connectionState.value = connected
                        GlassesLinkState.connected.value = connected
                        if (!connected) screenMounted = false
                    }
                    // Glasses battery for the HUD's top-right corner; stamped onto snapshots in update().
                    glassesBattery = if (connected) {
                        runCatching { Evs.instance().glasses().batteryPercentage() }.getOrNull()?.takeIf { it in 0..100 }
                    } else {
                        null
                    }
                    val cfg = configState.value
                    val pairedId = cfg.maverickDeviceId
                    if (pairedId != null && !connected && !comm.isConnecting()) {
                        // The SDK can lose its configured device across process restarts; restore
                        // it from our prefs so a ride reliably reconnects instead of sitting idle.
                        if (!comm.hasConfiguredDevice()) {
                            Timber.i("Restoring configured device $pairedId from prefs")
                            runCatching { comm.setDeviceInfo(pairedId, cfg.maverickDeviceName ?: "") }
                                .onFailure { Timber.w(it, "setDeviceInfo restore failed") }
                        }
                        if (comm.hasConfiguredDevice()) {
                            Timber.i("Auto-connecting to Maverick")
                            comm.connectSecured()
                        }
                    }
                }.onFailure { Timber.w(it, "connection loop error") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class) // screens().addScreen touches a UInt screen id
    private fun mountScreenIfNeeded() {
        if (screenMounted) return
        runCatching {
            if (!Evs.instance().comm().isConnected()) return
            Evs.instance().screens().addScreen(hudScreen)
            // The glasses display is off by default — without this the screen mounts but
            // nothing is shown.
            Evs.instance().display().turnDisplayOn()
            screenMounted = true
            Timber.i("HudScreen mounted; display on=${runCatching { Evs.instance().display().isDisplayOn() }.getOrNull()}")
        }.onFailure { Timber.w(it, "Failed to mount HudScreen") }
    }

    private fun startPageCycler() {
        scope.launch {
            while (isActive) {
                val cfg = configState.value
                delay(cfg.autoCycleMs.coerceAtLeast(1_000L))
                if (configState.value.pageMode == PageMode.AUTO) {
                    val count = lastSnapshot.pages.size
                    if (count > 1) {
                        pageIndex = (pageIndex + 1) % count
                        hudScreen.apply(lastSnapshot.copy(pageIndex = pageIndex))
                    }
                }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
