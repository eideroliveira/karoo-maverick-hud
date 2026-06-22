package com.eider.karoomaverickhud.maverick

import UIKit.app.data.TouchDirection
import android.content.Context
import com.eider.karoomaverickhud.extension.HudSnapshot
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.HudPreferences
import com.eider.karoomaverickhud.settings.PageMode
import com.everysight.evskit.android.Evs
import io.hammerhead.karooext.models.RideState
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
    /** True while the SDK is mid-connect (paired but link not yet up) — shown as "Reconnecting…". */
    val connecting = MutableStateFlow(value = false)
    /** Current display brightness 0..100, null when disconnected/unknown. */
    val brightness = MutableStateFlow<Int?>(value = null)
    /** True when the glasses' auto-brightness is enabled (overrides the manual level). */
    val autoBrightness = MutableStateFlow(value = false)
    /** Glasses battery 0..100, null when disconnected/unknown — the ride-limiting resource. Also
     *  mirrored for the Karoo sensor report (see
     *  [com.eider.karoomaverickhud.extension.MaverickDeviceProvider]). */
    val battery = MutableStateFlow<Int?>(value = null)
    /** BLE signal as 0..3 bars (RSSI-derived); 0 when disconnected or not yet read. */
    val signal = MutableStateFlow(value = 0)
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

    // The pinned-page index from the last snapshot, so we snap to a segment/climb page only on the
    // rising edge (and when the pin changes) rather than yanking back every tick — the rider can
    // still flip away mid-segment. Null means nothing is pinned.
    @Volatile private var lastPinnedPage: Int? = null

    // The last snapshot the ride pipeline produced (as opposed to a preview frame). Used to snap
    // the glasses straight back to realtime when an open settings preview clears, so we don't sit
    // on a stale demo frame waiting for the next pipeline tick (which may not come if the ride is
    // paused and no sensors are emitting).
    @Volatile private var lastRideSnapshot: HudSnapshot? = null

    // Latest glasses battery %, refreshed by the connection loop; null when disconnected. Exposed as
    // a flow so the ride pipeline can slow its refresh as the battery drains (see BatteryWarn).
    private val _glassesBattery = MutableStateFlow<Int?>(value = null)
    val glassesBattery: StateFlow<Int?> = _glassesBattery.asStateFlow()
    // Wall-clock of the last over-the-air battery read, so we refresh it on a slow cadence
    // (battery moves ~1%/min) instead of every liveness tick.
    private var lastBatteryReadAt = 0L

    // Ride state, supplied by the extension via [start]. We don't gate the link on it; it only
    // re-arms the fast-retry window when a ride starts (so a backed-off link reconnects promptly).
    private var rideState: StateFlow<RideState>? = null

    // Wall-clock deadline for the *fast* connect-retry window. Past it the loop keeps retrying but
    // at the slow idle cadence, so a paired-but-absent device stops hammering the BLE radio while
    // still recovering on its own. Re-armed on a fresh start, a drop after a good connect, a ride
    // start, or a live preview.
    @Volatile private var retryUntil = 0L

    // Centre control-window state (toggled by long-tap). Brightness 0..100, signal 0..3 bars.
    @Volatile private var controlOpen = false
    private var ctrlBrightness = 50
    private var ctrlAuto = false
    @Volatile private var ctrlSignal = 0

    private var connectionJob: Job? = null

    /**
     * Start the extension's glasses work: connect to the paired Maverick and then stay connected,
     * reconnecting on drops. Connect attempts are bounded by [retryUntil] so an absent device isn't
     * hammered forever; the window re-arms on a ride start (see [startRetryArmer]). Released in
     * [shutdown].
     */
    fun start(rideState: StateFlow<RideState>) {
        this.rideState = rideState
        armRetry() // try to connect for the first window from process start
        hudScreen.onTouchPad = ::handleTouch
        startConnectionLoop()
        startRetryArmer()
        startPageCycler()
        startPreviewObserver()
    }

    /** Open a fresh connect-retry window. */
    private fun armRetry() {
        retryUntil = System.currentTimeMillis() + CONNECT_WINDOW_MS
    }

    /**
     * Re-arm the connect window now. Called when the rider pairs the glasses through the native
     * Karoo Sensors flow — the paired id is written to prefs (which the loop reads), and this makes
     * the loop attempt the connect immediately rather than waiting for a ride or giving up.
     */
    fun requestConnect() = armRetry()

    /**
     * Re-arm the connect window when a ride starts. If the glasses were absent at app launch and we
     * gave up, beginning a ride is the moment we most want them back — so try again for a window.
     */
    private fun startRetryArmer() {
        val rs = rideState ?: return
        scope.launch {
            var wasIdle = rs.value is RideState.Idle
            rs.collect { state ->
                val idle = state is RideState.Idle
                if (wasIdle && !idle && !_connectionState.value) {
                    Timber.i("Ride started while disconnected — re-arming Maverick connect window")
                    armRetry()
                }
                wasIdle = idle
            }
        }
    }

    fun update(snapshot: HudSnapshot) {
        // While the settings app is pushing a live preview, it owns the glasses — ignore the
        // ride pipeline so the two don't fight over the screen.
        if (HudState.previewSnapshot.value != null) return
        // A live segment/climb page takes over: snap to it on the rising edge (and when the pinned
        // page changes, e.g. segment→climb), then leave the rider free to flip while it stays
        // pinned. Cleared pins resume normal cycling without a snap.
        val pin = snapshot.pinnedPage
        if (pin != null && pin != lastPinnedPage && pin < snapshot.pages.size) pageIndex = pin
        lastPinnedPage = pin
        // Clamp in case the layout shrank (e.g. switched to a Karoo page with fewer fields).
        if (snapshot.pages.isNotEmpty() && (pageIndex >= snapshot.pages.size)) pageIndex = 0
        val stamped = snapshot.copy(pageIndex = pageIndex, battery = _glassesBattery.value)
        lastRideSnapshot = stamped
        // Skip redundant pushes: when idle/paused or the sensors are steady, the pipeline keeps
        // ticking out identical frames that would otherwise re-flush the glasses every interval.
        // Equality is structural (HudSnapshot/HudCell are data classes).
        if (stamped != lastSnapshot) publish(stamped)
        mountScreenIfNeeded()
    }

    /** Mirror the settings app's live preview onto the glasses while it's open. */
    private fun startPreviewObserver() {
        scope.launch {
            HudState.previewSnapshot.collect { preview ->
                if (preview != null) {
                    publish(preview.copy(battery = _glassesBattery.value))
                    mountScreenIfNeeded()
                } else {
                    // Preview cleared (settings backgrounded/closed): restore the last ride frame
                    // immediately so the glasses snap back to realtime instead of holding the demo
                    // frame until — or unless — the ride pipeline emits again.
                    lastRideSnapshot?.let {
                        publish(it.copy(battery = _glassesBattery.value))
                        mountScreenIfNeeded()
                    }
                }
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
     * Temple-pad gestures.
     *  - Closed: long-tap opens the control window; forward/back flip pages (in any page mode —
     *    AUTO keeps cycling from wherever they land). A bare tap is swallowed so accidental
     *    pad touches don't bring up the window mid-ride.
     *  - Open: backward (read as "swipe down") dismisses; forward = brightness +10;
     *    tap = brightness −10; long-tap toggles auto-brightness.
     */
    private fun handleTouch(direction: TouchDirection) {
        Timber.d("touch=$direction controlOpen=$controlOpen bright=$ctrlBrightness auto=$ctrlAuto")
        if (controlOpen) {
            when (direction) {
                TouchDirection.backward -> toggleControl()
                TouchDirection.forward -> adjustBrightness(+10)
                TouchDirection.tap -> adjustBrightness(-10)
                TouchDirection.longTap -> toggleAuto()
                else -> {}
            }
            return
        }
        when (direction) {
            TouchDirection.longTap -> toggleControl()
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
                Timber.d("control open: read bright=$ctrlBrightness auto=$ctrlAuto")
            }.onFailure { Timber.w(it, "read brightness") }
        }
        pushControl()
    }

    private fun adjustBrightness(delta: Int) {
        ctrlBrightness = (ctrlBrightness + delta).coerceIn(0, 100)
        ctrlAuto = false
        runCatching {
            // Always force auto off first — the firmware will keep overriding our manual level
            // otherwise (the bug the user hit: brightness wouldn't climb back to 100% with auto on).
            Evs.instance().display().autoBrightness().enable(isEnabled = false)
            Evs.instance().display().setBrightness(ctrlBrightness.toShort())
            Timber.d("setBrightness($ctrlBrightness) → readback=${runCatching { Evs.instance().display().getBrightness().toInt() }.getOrNull()}")
        }.onFailure { Timber.w(it, "set brightness") }
        GlassesLinkState.brightness.value = ctrlBrightness
        GlassesLinkState.autoBrightness.value = false
        pushControl()
    }

    private fun toggleAuto() {
        ctrlAuto = !ctrlAuto
        runCatching {
            Evs.instance().display().autoBrightness().enable(ctrlAuto)
            Timber.d("autoBrightness.enable($ctrlAuto) → readback=${runCatching { Evs.instance().display().autoBrightness().isEnabled() }.getOrNull()}")
        }.onFailure { Timber.w(it, "toggle auto-brightness") }
        GlassesLinkState.autoBrightness.value = ctrlAuto
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
     * Connect to the paired glasses and keep them connected, mirroring link state for the UI (the
     * status-tile data field reads [GlassesLinkState]). The link is *not* ride-gated: once up it
     * stays up, reconnecting on drops.
     *
     * Reconnect cadence is bounded to spare the Karoo's battery. After a fresh start, a drop, a ride
     * start, or a live preview we fast-retry for [CONNECT_WINDOW_MS]; past that the loop keeps
     * retrying but at the slow [IDLE_POLL_INTERVAL_MS] cadence, so a pair that's been switched off
     * stops the futile fast scans yet still reconnects on its own when it comes back.
     *
     * The over-the-air reads are also trimmed: brightness/auto are seeded on connect and re-read
     * only while the control window is open (the user is the only thing that changes them), and
     * battery % refreshes on a slow cadence ([BATTERY_INTERVAL_MS]). RSSI is read each connected
     * tick since the signal bars feed both the control window and the data-field tile.
     */
    private fun startConnectionLoop() {
        connectionJob = scope.launch {
            while (isActive) {
                var nextDelay = IDLE_POLL_INTERVAL_MS
                runCatching {
                    val comm = Evs.instance().comm()
                    val wasConnected = _connectionState.value
                    val connected = comm.isConnected()
                    val justConnected = connected && !wasConnected
                    val justDropped = !connected && wasConnected
                    if (connected != wasConnected) {
                        _connectionState.value = connected
                        GlassesLinkState.connected.value = connected
                        if (!connected) screenMounted = false
                    }
                    // A drop after a good connection is a transient (out of range, glasses slept) —
                    // re-arm the fast-retry window so we work to get back promptly.
                    if (justDropped) armRetry()
                    // "Reconnecting…" state for the data-field status tile.
                    GlassesLinkState.connecting.value =
                        !connected && runCatching { comm.isConnecting() }.getOrDefault(false)

                    if (connected) {
                        // Battery (ride-limiting resource + status tile + BatteryWarn slowdown):
                        // refresh on a slow cadence plus once on connect — it moves ~1%/min.
                        val now = System.currentTimeMillis()
                        if (justConnected || now - lastBatteryReadAt >= BATTERY_INTERVAL_MS) {
                            val pct = runCatching { Evs.instance().glasses().batteryPercentage() }
                                .getOrNull()?.takeIf { it in 0..100 }
                            _glassesBattery.value = pct
                            GlassesLinkState.battery.value = pct
                            lastBatteryReadAt = now
                        }
                        // Brightness/auto mirror for the data field. Only the user changes these (via
                        // the control window / data-field tap, which write the mirror directly), so
                        // seed once on connect and re-read while the control window is open — no need
                        // to poll the glasses for them every tick.
                        if (justConnected || controlOpen) {
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
                        }
                        // Signal bars feed both the control window and the data-field tile, so keep
                        // RSSI fresh every connected tick (one cheap read at the connected cadence).
                        runCatching {
                            comm.requestRssiRead { rssi ->
                                ctrlSignal = rssiToBars(rssi)
                                GlassesLinkState.signal.value = ctrlSignal
                                if (controlOpen) pushControl()
                            }
                        }
                    } else {
                        _glassesBattery.value = null
                        GlassesLinkState.battery.value = null
                        GlassesLinkState.brightness.value = null
                        GlassesLinkState.autoBrightness.value = false
                        GlassesLinkState.signal.value = 0
                    }
                    // The fast-retry window covers a live preview (rider in settings, wants the link
                    // now) and the bounded window after a start/drop/ride. Past it we still attempt,
                    // just at the slow idle cadence chosen below.
                    val previewing = HudState.previewSnapshot.value != null
                    val armed = previewing || System.currentTimeMillis() < retryUntil

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

                    nextDelay = when {
                        controlOpen -> CONTROL_INTERVAL_MS       // responsive brightness/signal
                        connected -> CONNECTED_INTERVAL_MS       // hold the link, catch drops
                        armed -> CONNECT_RETRY_INTERVAL_MS       // fast retry within the window
                        else -> IDLE_POLL_INTERVAL_MS            // backed off; slow retry, still recovers
                    }
                }.onFailure { Timber.w(it, "connection loop error") }
                delay(nextDelay)
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
                if (lastSnapshot.pinnedPage != null) continue // a segment/climb page is pinned
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

    /** Outcome of a data-field tap, so the extension knows whether it must open the pair UI. */
    enum class TapResult { BRIGHTNESS, RECONNECTING, NEEDS_PAIR }

    /**
     * Single tap on the Karoo data field. Context-aware: cycle brightness while connected,
     * otherwise kick an immediate reconnect — or report NEEDS_PAIR if nothing is paired yet so
     * the extension can open the pair flow.
     */
    fun onFieldTap(): TapResult {
        if (_connectionState.value) {
            cycleBrightness()
            return TapResult.BRIGHTNESS
        }
        if (configState.value.maverickDeviceId == null) return TapResult.NEEDS_PAIR
        forceReconnect()
        return TapResult.RECONNECTING
    }

    /**
     * Re-assert the paired device and issue a secured connect, unless a link is already up or
     * forming. The connection loop also auto-reconnects every couple of seconds; this is the
     * rider's immediate "try now" so a drop doesn't feel like it's hanging.
     */
    private fun forceReconnect() {
        scope.launch {
            runCatching {
                val comm = Evs.instance().comm()
                if (comm.isConnected() || comm.isConnecting()) return@launch
                val cfg = configState.value
                val id = cfg.maverickDeviceId ?: return@launch
                if (!comm.hasConfiguredDevice()) comm.setDeviceInfo(id, cfg.maverickDeviceName ?: "")
                if (comm.hasConfiguredDevice()) {
                    Timber.i("Manual reconnect from data-field tap")
                    comm.connectSecured()
                }
            }.onFailure { Timber.w(it, "manual reconnect failed") }
        }
    }

    companion object {
        /** How long to fast-retry a connect before backing off to the slow idle cadence. */
        private const val CONNECT_WINDOW_MS = 3 * 60_000L
        /** Snappy cadence while the control window is open (live brightness/signal feedback). */
        private const val CONTROL_INTERVAL_MS = 1_000L
        /** Connected: hold the link and notice a drop reasonably quickly. */
        private const val CONNECTED_INTERVAL_MS = 4_000L
        /** Disconnected but still inside the fast-retry window: retry briskly. */
        private const val CONNECT_RETRY_INTERVAL_MS = 2_000L
        /** Backed-off retry cadence once the fast window passes (still recovers on its own). */
        private const val IDLE_POLL_INTERVAL_MS = 30_000L
        /** How often to refresh the glasses battery % over the air; it moves ~1%/min. */
        private const val BATTERY_INTERVAL_MS = 60_000L
    }
}
