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
    /** Current display brightness 0..100, null when disconnected/unknown. */
    val brightness = MutableStateFlow<Int?>(value = null)
    /** True when the glasses' auto-brightness is enabled (overrides the manual level). */
    val autoBrightness = MutableStateFlow(value = false)
}

/**
 * The latest HUD snapshot being shown on the glasses, mirrored here so the in-ride Karoo data
 * field ([com.eider.karoomaverickhud.extension.GlassesDataType]) can render the same values for
 * on-device debugging without a reference to the [MaverickBridge] instance.
 */
object HudState {
    val snapshot = MutableStateFlow(HudSnapshot.empty)

    /**
     * A preview snapshot pushed by the open settings activity so the rider sees their config
     * live on the glasses while editing. Non-null overrides the ride pipeline; cleared (null)
     * when settings closes. [MaverickBridge] honours it.
     */
    val previewSnapshot = MutableStateFlow<HudSnapshot?>(null)
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

    // Centre control-window state (toggled by long-tap). Brightness 0..100, signal 0..3 bars.
    @Volatile private var controlOpen = false
    private var ctrlBrightness = 50
    private var ctrlAuto = false
    @Volatile private var ctrlSignal = 0

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
        startPreviewObserver()
    }

    fun update(snapshot: HudSnapshot) {
        // While the settings app is pushing a live preview, it owns the glasses — ignore the
        // ride pipeline so the two don't fight over the screen.
        if (HudState.previewSnapshot.value != null) return
        // Clamp in case the layout shrank (e.g. switched to a Karoo page with fewer fields).
        if (snapshot.pages.isNotEmpty() && (pageIndex >= snapshot.pages.size)) pageIndex = 0
        publish(snapshot.copy(pageIndex = pageIndex, battery = glassesBattery))
        mountScreenIfNeeded()
    }

    /** Mirror the settings app's live preview onto the glasses while it's open. */
    private fun startPreviewObserver() {
        scope.launch {
            HudState.previewSnapshot.collect { preview ->
                if (preview != null) {
                    publish(preview.copy(battery = glassesBattery))
                    mountScreenIfNeeded()
                }
                // On clear (null) we leave the last frame; the next ride-pipeline update restores it.
            }
        }
    }

    /** Push a snapshot to the glasses and mirror it for the Karoo data field. */
    private fun publish(s: HudSnapshot) {
        lastSnapshot = s
        hudScreen.apply(s)
        HudState.snapshot.value = s
    }

    /**
     * Cycle the glasses' display brightness through 50% → 75% → 100% → auto → 50%.
     * Used by a single tap on the Karoo data field. No-op while disconnected.
     */
    fun cycleBrightness() {
        if (!_connectionState.value) return
        val auto = runCatching { Evs.instance().display().autoBrightness().isEnabled() }.getOrElse { ctrlAuto }
        val current = runCatching { Evs.instance().display().getBrightness().toInt() }.getOrElse { ctrlBrightness }
        val (nextAuto, nextLevel) = when {
            auto -> false to 50
            current < 50 -> false to 50
            current < 75 -> false to 75
            current < 100 -> false to 100
            else -> true to current
        }
        runCatching {
            if (nextAuto) {
                Evs.instance().display().autoBrightness().enable(isEnabled = true)
            } else {
                Evs.instance().display().autoBrightness().enable(isEnabled = false)
                Evs.instance().display().setBrightness(nextLevel.toShort())
            }
        }.onFailure { Timber.w(it, "cycleBrightness failed") }
        ctrlAuto = nextAuto
        ctrlBrightness = nextLevel
        GlassesLinkState.brightness.value = nextLevel
        GlassesLinkState.autoBrightness.value = nextAuto
        if (controlOpen) pushControl()
    }

    /**
     * Temple-pad gestures. A single tap toggles the centre control window. While it's open,
     * forward/back adjust brightness and a long-tap toggles auto-brightness; while it's closed,
     * forward/back flip the page (any mode — AUTO keeps cycling from wherever they land).
     */
    private fun handleTouch(direction: TouchDirection) {
        if (controlOpen) {
            when (direction) {
                TouchDirection.tap -> toggleControl()
                TouchDirection.forward -> adjustBrightness(+10)
                TouchDirection.backward -> adjustBrightness(-10)
                TouchDirection.longTap -> toggleAuto()
                else -> {}
            }
            return
        }
        when (direction) {
            TouchDirection.tap -> toggleControl()
            TouchDirection.forward -> changePage(+1)
            TouchDirection.backward -> changePage(-1)
            else -> {}
        }
    }

    private fun changePage(delta: Int) {
        val count = lastSnapshot.pages.size
        if (count <= 1) return
        pageIndex = (pageIndex + delta + count) % count
        publish(lastSnapshot.copy(pageIndex = pageIndex))
    }

    private fun toggleControl() {
        controlOpen = !controlOpen
        if (controlOpen) {
            runCatching {
                ctrlBrightness = Evs.instance().display().getBrightness().toInt()
                ctrlAuto = Evs.instance().display().autoBrightness().isEnabled()
            }.onFailure { Timber.w(it, "read brightness") }
        }
        pushControl()
    }

    private fun adjustBrightness(delta: Int) {
        ctrlBrightness = (ctrlBrightness + delta).coerceIn(0, 100)
        ctrlAuto = false
        runCatching {
            Evs.instance().display().autoBrightness().enable(isEnabled = false)
            Evs.instance().display().setBrightness(ctrlBrightness.toShort())
        }.onFailure { Timber.w(it, "set brightness") }
        pushControl()
    }

    private fun toggleAuto() {
        ctrlAuto = !ctrlAuto
        runCatching { Evs.instance().display().autoBrightness().enable(ctrlAuto) }
            .onFailure { Timber.w(it, "toggle auto-brightness") }
        pushControl()
    }

    private fun pushControl() {
        hudScreen.setControl(controlOpen, ctrlBrightness, ctrlAuto, ctrlSignal)
    }

    private fun rssiToBars(rssi: Int): Int = when {
        rssi >= -60 -> 3
        rssi >= -72 -> 2
        rssi >= -85 -> 1
        else -> 0
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
                        runCatching { Evs.instance().glasses().batteryPercentage() }.getOrNull()?.takeIf { it in (0..100) }
                    } else {
                        null
                    }
                    // Brightness/auto mirror for the Karoo data field; cleared on disconnect.
                    if (connected) {
                        val b = runCatching { Evs.instance().display().getBrightness().toInt() }
                            .getOrNull()?.takeIf { it in 0..100 }
                        val a = runCatching { Evs.instance().display().autoBrightness().isEnabled() }
                            .getOrDefault(false)
                        if (b != null) {
                            ctrlBrightness = b
                            GlassesLinkState.brightness.value = b
                        }
                        ctrlAuto = a
                        GlassesLinkState.autoBrightness.value = a
                    } else {
                        GlassesLinkState.brightness.value = null
                        GlassesLinkState.autoBrightness.value = false
                    }
                    // Signal bars for the control window (BLE RSSI → 0..3).
                    if (connected) {
                        runCatching {
                            comm.requestRssiRead { rssi ->
                                ctrlSignal = rssiToBars(rssi)
                                if (controlOpen) pushControl()
                            }
                        }
                    }
                    val cfg = configState.value
                    val pairedId = cfg.maverickDeviceId
                    if ((pairedId != null) && !connected && !comm.isConnecting()) {
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
                if (HudState.previewSnapshot.value != null) continue // preview owns paging
                if (configState.value.pageMode == PageMode.AUTO) {
                    val count = lastSnapshot.pages.size
                    if (count > 1) {
                        pageIndex = (pageIndex + 1) % count
                        publish(lastSnapshot.copy(pageIndex = pageIndex))
                    }
                }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
