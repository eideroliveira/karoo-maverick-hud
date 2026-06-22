package com.eider.karoomaverickhud.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.eider.karoomaverickhud.maverick.MaverickBridge
import com.eider.karoomaverickhud.settings.HudPreferences
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.PageMode
import com.eider.karoomaverickhud.settings.SettingsActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import java.util.Calendar
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
    private lateinit var deviceProvider: MaverickDeviceProvider
    private lateinit var rideStateFlow: StateFlow<RideState>

    // In-ride field that shows Maverick connection status and current brightness. Reads the
    // process-wide GlassesLinkState, so it needs no reference to [maverick]; a tap broadcasts
    // [ACTION_CYCLE_BRIGHTNESS] back to us.
    override val types by lazy { listOf(GlassesDataType(extension)) }

    private val cycleBrightnessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::maverick.isInitialized) maverick.cycleBrightness()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("RideHudExtension onCreate")

        karoo = KarooSystemService(applicationContext)
        maverick = MaverickBridge(applicationContext, scope)
        deviceProvider = MaverickDeviceProvider(applicationContext, scope, maverick, extension)

        karoo.connect { connected ->
            Timber.i("Karoo system connected=$connected")
        }

        rideStateFlow = karoo.consumerFlow<RideState>()
            .stateIn(scope, SharingStarted.Eagerly, RideState.Idle)

        // The bridge gates the glasses link on ride state — held while recording/paused, released
        // when idle — so it needs the same ride-state feed the pipeline uses.
        maverick.start(rideStateFlow)

        ContextCompat.registerReceiver(
            this,
            cycleBrightnessReceiver,
            IntentFilter(ACTION_CYCLE_BRIGHTNESS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        startPipeline()
        startMaverickHealthMonitor()
    }

    /**
     * Ride-menu actions, both reliable in-ride triggers the Karoo guarantees to deliver (unlike a
     * data-field tap):
     *  - "pair" opens the app straight into the pair flow (GPS is on during a ride, which the BLE
     *    scan needs).
     *  - "configure" opens the settings app — the easy way into configuration without leaving the
     *    ride screen for the Extensions menu.
     */
    override fun onBonusAction(actionId: String) {
        Timber.i("onBonusAction $actionId")
        when (actionId) {
            "pair" -> openSettings(autoPair = true)
            "configure" -> openSettings(autoPair = false)
        }
    }

    private fun openSettings(autoPair: Boolean) {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (autoPair) intent.putExtra(SettingsActivity.EXTRA_AUTO_PAIR, true)
        startActivity(intent)
    }

    /**
     * Device-provider hooks (enabled by `scansDevices="true"`): let the rider pair and monitor the
     * glasses from the native Karoo Sensors UI. Delegated to [MaverickDeviceProvider]; the link
     * stays owned by [MaverickBridge].
     */
    override fun startScan(emitter: Emitter<Device>) = deviceProvider.startScan(emitter)

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) =
        deviceProvider.connectDevice(uid, emitter)

    override fun onDestroy() {
        Timber.i("RideHudExtension onDestroy")
        runCatching { unregisterReceiver(cycleBrightnessReceiver) }
        scope.cancel()
        maverick.shutdown()
        karoo.disconnect()
        super.onDestroy()
    }

    companion object {
        /** Broadcast sent by a tap on the Karoo data field to cycle glasses brightness. */
        const val ACTION_CYCLE_BRIGHTNESS = "com.eider.karoomaverickhud.CYCLE_BRIGHTNESS"
    }

    /**
     * Streams the selected fields into HUD snapshots. The Karoo's native sensor cadence is
     * ~1 Hz, so we sample at 1000 ms. Ride state rides along in each snapshot so the glasses
     * show "waiting for ride" when idle and the HUD when recording.
     */
    @Suppress("OPT_IN_USAGE")
    private fun startPipeline() {
        val configFlow = HudPreferences.flow(applicationContext)
            .stateIn(scope, SharingStarted.Eagerly, HudConfig.DEFAULT)

        // Only consumed in FOLLOW_KAROO mode, but shared cheaply via stateIn.
        val activePageFlow = karoo.consumerFlow<ActiveRidePage>()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // Whether a structured workout is loaded — WORKOUT_STEP_COUNT only streams non-zero while
        // a workout file is present. Gates the (user-editable) workout page below.
        val workoutActiveFlow = karoo.streamDataFlow(DataType.Type.WORKOUT_INTERVAL_COUNT)
            .map { FieldFormat.workoutActive(it) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

        // The fields to render, as pages of data-type ids, capped to the cells the chosen row
        // count exposes. FOLLOW_KAROO mirrors the active Karoo page's top fields; AUTO/MANUAL
        // use the user's custom pages. A live workout prepends the configured workout page.
        val layoutFlow = combine(configFlow, activePageFlow, workoutActiveFlow) { cfg, active, workoutActive ->
            val cap = cellsForRows(cfg.rows)
            val base = if (cfg.pageMode == PageMode.FOLLOW_KAROO) {
                val fields = active?.page?.elements?.asSequence()?.map { it.dataTypeId }?.take(cap)?.toList().orEmpty()
                if (fields.isEmpty()) emptyList() else listOf(fields)
            } else {
                cfg.pages.asSequence().map { it.take(cap) }.filter { it.isNotEmpty() }.toList()
            }
            if (workoutActive && cfg.workoutPage.isNotEmpty()) listOf(cfg.workoutPage.take(cap)) + base else base
        }.distinctUntilChanged()

        // Re-evaluated when the (configurable) refresh interval changes so the setting actually
        // takes effect — previously it was read once at flow assembly and pinned for the session.
        val refreshMsFlow = configFlow.map { it.refreshIntervalMs }.distinctUntilChanged()

        refreshMsFlow.flatMapLatest { refreshMs ->
            layoutFlow.flatMapLatest { layout ->
                // Hidden helper streams (subscribed but never rendered, so only [ids] grows):
                // CADENCE colouring needs the live power reading for its under-gear rule, and the
                // live POWER/CADENCE fields need their workout target streams to render
                // "value/target" mid-workout even when no target field is on a page. The target
                // streams are Idle outside a workout, so the extra subscriptions cost nothing.
                val displayed = layout.asSequence().flatten().distinct().toList()
                val ids = buildList {
                    addAll(displayed)
                    if (DataType.Type.CADENCE in displayed && DataType.Type.POWER !in displayed) add(DataType.Type.POWER)
                    if (DataType.Type.POWER in displayed && DataType.Type.WORKOUT_POWER_TARGET !in displayed) add(DataType.Type.WORKOUT_POWER_TARGET)
                    if (DataType.Type.CADENCE in displayed && DataType.Type.WORKOUT_CADENCE_TARGET !in displayed) add(DataType.Type.WORKOUT_CADENCE_TARGET)
                }
                val cellsFlow = if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    // One context per layout subscription — carries the last power reading and the
                    // under-gear dwell timer that the cadence colour rule needs. Re-created on every
                    // layout change so a page swap doesn't leak a stale timer.
                    val ctx = FormatContext()
                    val streams = ids.map { id -> karoo.streamDataFlow(id).map { state -> id to state } }
                    merge(*streams.toTypedArray())
                        .scan(emptyMap<String, HudCell>()) { acc, (id, state) ->
                            val cfg = configFlow.value
                            val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
                            val gear = GearLayout(cfg.gear.front, cfg.gear.rear, cfg.gear.display)
                            acc + (id to FieldFormat.format(id, state, cfg.imperial, zones, ctx, gear))
                        }
                }
                cellsFlow.map { cells -> layout to cells }
            }.sample(refreshMs)
        }
            .combine(rideStateFlow) { (layout, cells), ride ->
                HudSnapshot(
                    pages = layout.map { page -> page.map { id -> cells[id] ?: HudCell.blank(id) } },
                    paused = ride is RideState.Paused,
                    recording = ride is RideState.Recording,
                    pageIndex = 0,
                    rows = configFlow.value.rows,
                    clock = if (configFlow.value.showClock) currentClock() else "",
                    showIcons = configFlow.value.showIcons,
                    // battery (glasses %) is stamped by MaverickBridge, which owns the Evs link.
                )
            }
            .onEach { snapshot -> maverick.update(snapshot) }
            .launchIn(scope)
    }

    /** Current time of day as "HH:mm" from the Karoo's clock, for the HUD's top-left corner. */
    private fun currentClock(): String {
        val cal = Calendar.getInstance()
        return "%02d:%02d".format(cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE])
    }

    /**
     * Surface a Karoo in-ride alert the first time the glasses drop. We don't spam —
     * the bridge owns the reconnect loop; this is just a user nudge.
     */
    private fun startMaverickHealthMonitor() {
        scope.launch {
            // Seed from the current state so the StateFlow's initial replay isn't mistaken for a
            // drop (which would fire a phantom "disconnected" alert the moment a ride starts).
            var lastConnected = maverick.connectionState.value
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
