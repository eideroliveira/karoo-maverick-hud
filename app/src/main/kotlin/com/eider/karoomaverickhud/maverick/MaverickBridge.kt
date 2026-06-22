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
    /** Current display brightness 0..100, null when disconnected/unknown. */
    val brightness = MutableStateFlow<Int?>(value = null)
    /** True when the glasses' auto-brightness is enabled (overrides the manual level). */
    val autoBrightness = MutableStateFlow(value = false)
    /** Latest glasses battery %, null when disconnected/unknown. Mirrored for the Karoo sensor
     *  report (see [com.eider.karoomaverickhud.extension.MaverickDeviceProvider]). */
    val battery = MutableStateFlow<Int?>(value = null)
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

    // The last snapshot the ride pipeline produced (as opposed to a preview frame). Used to snap
    // the glasses straight back to realtime when an open settings preview clears, so we don't sit
    // on a stale demo frame waiting for the next pipeline tick (which may not come if the ride is
    // paused and no sensors are emitting).
    @Volatile private var lastRideSnapshot: HudSnapshot? = null

    // Latest glasses battery %, refreshed by the connection loop; null when disconnected.
    @Volatile private var glassesBattery: Int? = null
    // Wall-clock of the last over-the-air battery read, so we can refresh it on a slow cadence
    // (battery moves ~1%/min) instead of every liveness tick.
    private var lastBatteryReadAt = 0L

    // Ride state, supplied by the extension via [start]. We don't gate the link on it; it only
    // re-arms the connect window when a ride starts (so a give-up earlier in the day still
    // reconnects for the ride).
    private var rideState: StateFlow<RideState>? = null

    // Wall-clock deadline for connect attempts. We keep retrying a paired-but-absent device only
    // until this passes, then give up (stop hammering the BLE radio) until something re-arms it —
    // a fresh process start, a drop after a successful connect, a ride starting, or a live preview.
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
        // Clamp in case the layout shrank (e.g. switched to a Karoo page with fewer fields).
        if (snapshot.pages.isNotEmpty() && (pageIndex >= snapshot.pages.size)) pageIndex = 0
        val stamped = snapshot.copy(pageIndex = pageIndex, battery = glassesBattery)
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
                    publish(preview.copy(battery = glassesBattery))
                    mountScreenIfNeeded()
                } else {
                    // Preview cleared (settings backgrounded/closed): restore the last ride frame
                    // immediately so the glasses snap back to realtime instead of holding the demo
                    // frame until — or unless — the ride pipeline emits again.
                    lastRideSnapshot?.let {
                        publish(it.copy(battery = glassesBattery))
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
     * Connect to the paired glasses and keep them connected, mirroring link state for the UI. The
     * link is *not* ride-gated: once up it stays up (reconnecting on drops). What's bounded is the
     * *attempting* — we only fire connects while inside the [retryUntil] window, so a paired device
     * that's simply absent (left at home, powered off) isn't hammered forever. A successful connect,
     * a subsequent drop, a live preview, or a ride start all re-open the window.
     *
     * Battery care kept from the earlier pass: the over-the-air reads (brightness, auto, RSSI) run
     * only when something shows them (control window open, or just-connected seeding), and battery %
     * refreshes on a slow cadence ([BATTERY_INTERVAL_MS]). Base cadence scales with need.
     */
    private fun startConnectionLoop() {
        connectionJob = scope.launch {
            while (isActive) {
                var nextDelay = IDLE_INTERVAL_MS
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
                    // re-arm so we actively work to get back, rather than honouring a window that may
                    // already have expired during a long ride.
                    if (justDropped) armRetry()

                    if (connected) {
                        // Battery for the HUD corner: refresh on a slow cadence, plus once on connect.
                        val now = System.currentTimeMillis()
                        if (justConnected || now - lastBatteryReadAt >= BATTERY_INTERVAL_MS) {
                            glassesBattery = runCatching { Evs.instance().glasses().batteryPercentage() }
                                .getOrNull()?.takeIf { it in 0..100 }
                            GlassesLinkState.battery.value = glassesBattery
                            lastBatteryReadAt = now
                        }
                        // Brightness/auto mirror for the Karoo data field. Only the user changes
                        // these (via the control window / data-field tap, which write the mirror
                        // directly), so we just seed once on connect and re-read while the control
                        // window is open — no need to poll the glasses for them every tick.
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
                        // Signal bars are only drawn inside the control window — read RSSI solely
                        // while it's open.
                        if (controlOpen) {
                            runCatching {
                                comm.requestRssiRead { rssi ->
                                    ctrlSignal = rssiToBars(rssi)
                                    if (controlOpen) pushControl()
                                }
                            }
                        }
                    } else {
                        glassesBattery = null
                        GlassesLinkState.battery.value = null
                        GlassesLinkState.brightness.value = null
                        GlassesLinkState.autoBrightness.value = false
                    }

                    // A live preview wants the link regardless of the retry window (the rider is in
                    // settings expecting to see the glasses); otherwise only attempt while armed.
                    val previewing = HudState.previewSnapshot.value != null
                    val mayAttempt = previewing || System.currentTimeMillis() < retryUntil

                    val cfg = configState.value
                    val pairedId = cfg.maverickDeviceId
                    if (mayAttempt && pairedId != null && !connected && !comm.isConnecting()) {
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
                        mayAttempt -> CONNECT_RETRY_INTERVAL_MS  // actively trying to connect
                        else -> IDLE_INTERVAL_MS                 // gave up; just tick over cheaply
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
        /** How long to keep attempting a connect before giving up on an absent device. */
        private const val CONNECT_WINDOW_MS = 3 * 60_000L
        /** Snappy cadence while the control window is open (live brightness/signal feedback). */
        private const val CONTROL_INTERVAL_MS = 1_000L
        /** Connected: hold the link and notice a drop reasonably quickly. */
        private const val CONNECTED_INTERVAL_MS = 4_000L
        /** Disconnected but still armed: retry briskly so the link comes up fast. */
        private const val CONNECT_RETRY_INTERVAL_MS = 2_000L
        /** Gave up (window elapsed, device absent): just keep the loop alive cheaply. */
        private const val IDLE_INTERVAL_MS = 10_000L
        /** How often to refresh the glasses battery % over the air; it moves ~1%/min. */
        private const val BATTERY_INTERVAL_MS = 60_000L
    }
}
