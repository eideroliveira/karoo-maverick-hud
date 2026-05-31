package com.eider.karoomaverickhud.maverick

import UIKit.app.data.TouchDirection
import android.content.Context
import android.os.SystemClock
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

    private var connectionJob: Job? = null

    /**
     * Start always-on, ride-independent work (page cycling). The glasses link is
     * opened on [onRideStart] and closed on [onRideEnd] — we don't hold a BLE link
     * outside a ride.
     */
    fun start() {
        hudScreen.onTouchPad = ::handleTouch
        startPageCycler()
    }

    fun update(snapshot: HudSnapshot) {
        // Clamp in case the layout shrank (e.g. switched to a Karoo page with fewer fields).
        if (snapshot.pages.isNotEmpty() && pageIndex >= snapshot.pages.size) pageIndex = 0
        val stamped = snapshot.copy(pageIndex = pageIndex)
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
     * Open the glasses link for a ride. Polls the SDK and retries [ensureConnected]
     * until the link is up. If it never connects within [CONNECT_TIMEOUT_MS] we give
     * up for this ride; once connected the loop stays alive to recover dropouts.
     */
    fun onRideStart() {
        if (connectionJob?.isActive == true) return
        if (configState.value.maverickDeviceId == null) {
            Timber.i("Ride started but no Maverick paired; not connecting")
            return
        }
        connectionJob = scope.launch {
            Timber.i("Ride started; connecting to Maverick (give up after ${CONNECT_TIMEOUT_MS / 1000}s)")
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            var everConnected = false
            while (isActive) {
                val connected = runCatching { Evs.instance().comm().isConnected() }.getOrDefault(defaultValue = false)
                if (connected != _connectionState.value) {
                    _connectionState.value = connected
                    GlassesLinkState.connected.value = connected
                    if (!connected) screenMounted = false
                }
                when {
                    connected -> everConnected = true
                    (!everConnected) && (SystemClock.elapsedRealtime() >= deadline) -> {
                        Timber.w("Maverick connect timed out after ${CONNECT_TIMEOUT_MS / 1000}s; giving up for this ride")
                        return@launch
                    }
                    else -> ensureConnected()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Close the glasses link and stop retrying. Called when the ride ends. */
    fun onRideEnd() {
        connectionJob?.cancel()
        connectionJob = null
        screenMounted = false
        if (_connectionState.value) _connectionState.value = false
        GlassesLinkState.connected.value = false
        runCatching { Evs.instance().comm().disconnect() }
            .onFailure { Timber.w(it, "Evs disconnect on ride end failed") }
    }

    private fun ensureConnected() {
        runCatching {
            val comm = Evs.instance().comm()
            if (comm.isConnected()) return
            if (comm.hasConfiguredDevice()) comm.connect()
        }.onFailure { Timber.w(it, "Evs reconnect failed") }
    }

    @OptIn(ExperimentalUnsignedTypes::class) // screens().addScreen touches a UInt screen id
    private fun mountScreenIfNeeded() {
        if (screenMounted) return
        runCatching {
            if (!Evs.instance().comm().isConnected()) return
            Evs.instance().screens().addScreen(hudScreen)
            screenMounted = true
            Timber.i("HudScreen mounted")
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
        /** Give up establishing the glasses link this long after a ride starts. */
        private const val CONNECT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
