package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.maverick.MaverickBridge
import com.eider.karoomaverickhud.settings.HudPreferences
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.PageMode
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class RideHudExtension : KarooExtension("maverick_hud", "0.1.0") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var karoo: KarooSystemService
    private lateinit var maverick: MaverickBridge
    private lateinit var rideStateFlow: StateFlow<RideState>

    // Tappable in-ride field that shows link status and opens pairing. Reads the
    // process-wide GlassesLinkState, so it needs no reference to [maverick].
    override val types by lazy { listOf(GlassesDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        Timber.i("RideHudExtension onCreate")

        karoo = KarooSystemService(applicationContext)
        maverick = MaverickBridge(applicationContext, scope)

        karoo.connect { connected ->
            Timber.i("Karoo system connected=$connected")
        }

        rideStateFlow = karoo.consumerFlow<RideState>()
            .stateIn(scope, SharingStarted.Eagerly, RideState.Idle)

        maverick.start()

        startRideLifecycle()
        startPipeline()
        startMaverickHealthMonitor()
    }

    override fun onDestroy() {
        Timber.i("RideHudExtension onDestroy")
        scope.cancel()
        maverick.shutdown()
        karoo.disconnect()
        super.onDestroy()
    }

    /**
     * One pipeline per running ride. Re-subscribes when the user changes field
     * selections in settings. The Karoo's native sensor cadence is ~1 Hz, so we
     * sample at 1000 ms — under-pulling vs over-pulling for BLE stability.
     */
    /**
     * Drive the glasses link off ride state: open it when recording begins, close it
     * when the ride returns to Idle. The bridge owns the connect-with-timeout, so we
     * just signal the start/end transitions here.
     */
    private fun startRideLifecycle() {
        scope.launch {
            var active = false
            rideStateFlow.collect { state ->
                val nowActive = state !is RideState.Idle
                if (nowActive && !active) {
                    Timber.i("Ride started -> opening Maverick link")
                    maverick.onRideStart()
                } else if (!nowActive && active) {
                    Timber.i("Ride ended -> closing Maverick link")
                    maverick.onRideEnd()
                }
                active = nowActive
            }
        }
    }

    @Suppress("OPT_IN_USAGE")
    private fun startPipeline() {
        val configFlow = HudPreferences.flow(applicationContext)
            .stateIn(scope, SharingStarted.Eagerly, HudConfig.DEFAULT)

        // Only consumed in FOLLOW_KAROO mode, but shared cheaply via stateIn.
        val activePageFlow = karoo.consumerFlow<ActiveRidePage>()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // The fields to render, as pages of data-type ids. FOLLOW_KAROO mirrors the
        // active Karoo page's top fields; AUTO/MANUAL use the user's custom pages.
        val layoutFlow = configFlow.combine(activePageFlow) { cfg, active ->
            if (cfg.pageMode == PageMode.FOLLOW_KAROO) {
                val fields = active?.page?.elements?.map { it.dataTypeId }?.take(MAX_CELLS).orEmpty()
                if (fields.isEmpty()) emptyList() else listOf(fields)
            } else {
                cfg.pages.map { it.take(MAX_CELLS) }.filter { it.isNotEmpty() }
            }
        }.distinctUntilChanged()

        layoutFlow.flatMapLatest { layout ->
            val ids = layout.flatten().distinct()
            val cellsFlow = if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val streams = ids.map { id -> karoo.streamDataFlow(id).map { state -> id to state } }
                merge(*streams.toTypedArray())
                    .scan(emptyMap<String, HudCell>()) { acc, (id, state) ->
                        acc + (id to FieldFormat.format(id, state, configFlow.value.imperial))
                    }
            }
            cellsFlow.map { cells -> layout to cells }
        }
            .sample(configFlow.value.refreshIntervalMs)
            .combine(rideStateFlow) { (layout, cells), ride ->
                val pages = layout.map { page -> page.map { id -> cells[id] ?: HudCell.blank(id) } }
                HudSnapshot(
                    pages = pages,
                    paused = ride is RideState.Paused,
                    recording = ride is RideState.Recording,
                    pageIndex = 0,
                )
            }
            .onEach { snapshot -> maverick.update(snapshot) }
            .launchIn(scope)
    }

    /**
     * Surface a Karoo in-ride alert the first time the glasses drop. We don't spam —
     * the bridge owns the reconnect loop; this is just a user nudge.
     */
    private fun startMaverickHealthMonitor() {
        scope.launch {
            var lastConnected = true
            maverick.connectionState.collect { connected ->
                // Only nudge on an *unexpected* drop mid-ride — the ride-end disconnect
                // is intentional and Idle by the time it lands, so it stays silent.
                if (lastConnected && !connected && (rideStateFlow.value !is RideState.Idle)) {
                    karoo.dispatch(
                        InRideAlert(
                            id = "maverick-disconnected",
                            icon = com.eider.karoomaverickhud.R.drawable.ic_hud,
                            title = "Maverick HUD",
                            detail = "Glasses disconnected",
                            autoDismissMs = 4_000L,
                            backgroundColor = com.eider.karoomaverickhud.R.color.hud_alert_bg,
                            textColor = com.eider.karoomaverickhud.R.color.hud_alert_text,
                        ),
                    )
                }
                lastConnected = connected
            }
        }
    }
}
