package com.eider.karoomaverickhud.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.eider.karoomaverickhud.maverick.Eco
import com.eider.karoomaverickhud.maverick.MaverickBridge
import com.eider.karoomaverickhud.maverick.SaverTuning
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
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
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

    // In-ride field that shows the Maverick at a glance (battery, signal, brightness). Reads the
    // process-wide GlassesLinkState, so it needs no reference to [maverick]; a tap broadcasts
    // [ACTION_GLASSES_TAP] back to us.
    override val types by lazy { listOf(GlassesDataType(extension)) }

    private val glassesTapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::maverick.isInitialized) return
            // Context-aware: toggle saver when connected, reconnect when down, pair when never paired.
            if (maverick.onFieldTap() == MaverickBridge.TapResult.NEEDS_PAIR) openSettings(autoPair = true)
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

        // The bridge keeps the link always-connected (not ride-gated); it uses the ride-state feed
        // only to re-arm its fast connect-retry window when a ride starts.
        maverick.start(rideStateFlow)

        ContextCompat.registerReceiver(
            this,
            glassesTapReceiver,
            IntentFilter(ACTION_GLASSES_TAP),
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
        runCatching { unregisterReceiver(glassesTapReceiver) }
        scope.cancel()
        maverick.shutdown()
        karoo.disconnect()
        super.onDestroy()
    }

    companion object {
        /** Broadcast sent by a tap on the Karoo data field — toggles battery-saver when connected, else reconnects/pairs. */
        const val ACTION_GLASSES_TAP = "com.eider.karoomaverickhud.GLASSES_TAP"
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

        // A Strava live segment is running (segment fields stream a positive distance-remaining only
        // while on a segment). Gates and pins the segment auto-page.
        val segmentActiveFlow = karoo.streamDataFlow(DataType.Type.SEGMENT_DISTANCE_REMAINING)
            .map { FieldFormat.segmentActive(it) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

        // On a climb (Karoo ClimbPro) — distance/elevation-to-top stream positive only on a climb.
        // Gates and pins the climb auto-page, which only shows when no segment is running.
        val climbActiveFlow = karoo.streamDataFlow(DataType.Type.CLIMB)
            .map { FieldFormat.climbActive(it) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

        // The loaded route — climbs + total length (for the radar) and the decoded path geometry
        // (for the trajectory map). Null whenever navigation is idle or following a free destination
        // (no total route distance to anchor progress against). A lightweight event consumer; the
        // polyline is decoded once here, not per frame.
        val routeFlow = karoo.consumerFlow<OnNavigationState>()
            .map { event ->
                (event.state as? OnNavigationState.NavigationState.NavigatingRoute)?.let {
                    RouteInfo(it.climbs, it.routeDistance, RouteTrajectory.decode(it.routePolyline), it.reversed)
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // Live position + heading (for projecting the trajectory map) and live grade (descent
        // detection + the map's grade readout). Both quiet to a safe default off-ride.
        val locationFlow = karoo.consumerFlow<OnLocationChanged>()
            .stateIn(scope, SharingStarted.Eagerly, null)
        val gradeFlow = karoo.streamDataFlow(DataType.Type.ELEVATION_GRADE)
            .stateIn(scope, SharingStarted.Eagerly, StreamState.Idle)
        val trajSpeedFlow = karoo.streamDataFlow(DataType.Type.SPEED)
            .stateIn(scope, SharingStarted.Eagerly, StreamState.Idle)

        // The heading-up trajectory to draw, or null when the map is disabled or there's no
        // route/position to project. Re-projected on each position tick; carries a small speed+grade
        // overlay so the map page isn't only a line.
        val trajectoryFlow = combine(
            configFlow.map { it.trajectoryEnabled }.distinctUntilChanged(),
            routeFlow, locationFlow, gradeFlow, trajSpeedFlow,
        ) { enabled, route, loc, gradeState, speedState ->
            if (!enabled || route == null || loc == null) {
                null
            } else {
                val pts = RouteTrajectory.project(
                    route.points, LatLng(loc.lat, loc.lng), loc.orientation, route.reversed,
                )
                if (pts.size < 2) {
                    null
                } else {
                    val cfg = configFlow.value
                    val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
                    Trajectory(
                        pts,
                        listOf(
                            FieldFormat.format(DataType.Type.SPEED, speedState, cfg.imperial, zones),
                            FieldFormat.format(DataType.Type.ELEVATION_GRADE, gradeState, cfg.imperial, zones),
                        ),
                    )
                }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

        // Whether the trajectory page should exist (route projectable) and whether it should auto-pin
        // (descending). Kept off the live trajectory values so the layout only churns on these flips.
        val trajAvailableFlow = trajectoryFlow.map { it != null }
            .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
        val descentActiveFlow = combine(trajectoryFlow, gradeFlow) { traj, gradeState ->
            traj != null && (FieldFormat.gradeOf(gradeState) ?: 0.0) <= RouteTrajectory.DESCENT_GRADE
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

        // The climb the rider is approaching, or null when none is within the look-ahead window
        // (~90 s out, capped at 1 km). Gated on the radar toggle so its streams cost nothing when
        // off, and suppressed once on the climb so the on-climb page takes over cleanly. Feeds both
        // the radar gating (via [radarActiveFlow]) and the synthetic radar cells.
        val nextClimbFlow = configFlow.map { it.radarEnabled }.distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(null)
                } else {
                    combine(
                        routeFlow,
                        karoo.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION),
                        karoo.streamDataFlow(DataType.Type.SPEED),
                        climbActiveFlow,
                    ) { route, dtd, speed, onClimb ->
                        if (route == null || onClimb) {
                            null
                        } else {
                            val progress = RouteRadar.routeProgress(route.routeDistance, RouteRadar.distanceToDestination(dtd))
                            RouteRadar.nextClimb(route.climbs, progress, RouteRadar.speed(speed))
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // Whether a climb is in the look-ahead window — the boolean that gates/pins the radar page.
        // Kept separate from [nextClimbFlow] so the live (continuously changing) climb values don't
        // churn the layout (and re-subscribe streams) every tick; only activation flips it.
        val radarActiveFlow = nextClimbFlow.map { it != null }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

        // The auto-page signals collapsed into one value, so the layout combine stays within the
        // typed-arity limit. Pin precedence (segment > climb > radar > descent-trajectory) and the
        // trajectory-page append are applied in the builder.
        val autoPagesFlow = combine(
            segmentActiveFlow, climbActiveFlow, radarActiveFlow, trajAvailableFlow, descentActiveFlow,
        ) { segment, climb, radar, trajAvailable, descent ->
            AutoPages(segment, climb, radar, trajAvailable, descent)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, AutoPages())

        // Display labels for any extension-provided fields the rider added to a page (MPA, time to
        // summit, …) so the generic renderer can unit-label them. Discovered once at start.
        val extLabels = KarooDataTypeCatalog.labels(applicationContext)

        // The fields to render, as pages of data-type ids, capped to the cells the chosen row
        // count exposes. FOLLOW_KAROO mirrors the active Karoo page's top fields; AUTO/MANUAL
        // use the user's custom pages. A live workout prepends the configured workout page; a live
        // Strava segment, an on-climb, or an approaching climb (radar) prepends its page and pins
        // the display to it. Precedence when several apply: segment > on-climb > radar.
        val layoutFlow = combine(
            configFlow, activePageFlow, workoutActiveFlow, autoPagesFlow,
        ) { cfg, active, workoutActive, auto ->
            val cap = cellsForRows(cfg.rows)
            val base = if (cfg.pageMode == PageMode.FOLLOW_KAROO) {
                val fields = active?.page?.elements?.asSequence()?.map { it.dataTypeId }?.take(cap)?.toList().orEmpty()
                if (fields.isEmpty()) emptyList() else listOf(fields)
            } else {
                cfg.pages.asSequence().map { it.take(cap) }.filter { it.isNotEmpty() }.toList()
            }
            val priority = mutableListOf<List<String>>()
            var pinned: Int? = null
            if (workoutActive && cfg.workoutPage.isNotEmpty()) priority.add(cfg.workoutPage.take(cap))
            if (auto.segment && cfg.segmentPage.isNotEmpty()) {
                pinned = priority.size
                priority.add(cfg.segmentPage.take(cap))
            } else if (auto.climb && cfg.climbPage.isNotEmpty()) {
                pinned = priority.size
                priority.add(cfg.climbPage.take(cap))
            } else if (auto.radar) {
                // Radar gating already honours the toggle and on-climb suppression (see nextClimbFlow).
                pinned = priority.size
                priority.add(HudConfig.DEFAULT_RADAR_PAGE.take(cap))
            }
            // The trajectory map page is appended last (so it's reachable by paging) when a route is
            // projectable, and auto-pinned on descents unless a higher-priority page already pinned.
            val pages = (priority + base).toMutableList()
            var trajectoryPageIndex: Int? = null
            if (auto.trajAvailable) {
                trajectoryPageIndex = pages.size
                pages.add(listOf(RouteTrajectory.PAGE_MARKER))
                if (pinned == null && auto.descent) pinned = trajectoryPageIndex
            }
            Layout(pages, pinned, trajectoryPageIndex)
        }.combine(Eco.critical) { layout, critical ->
            // Critical battery: collapse to one minimal page (speed + time) to squeeze out the final
            // minutes. Overrides every priority/pinned page; the streams it needs are subscribed
            // automatically since [cellsPipeline] derives its ids from the live layout.
            if (critical) Layout(listOf(SaverTuning.MINIMAL_PAGE), pinnedPage = null, trajectoryPageIndex = null) else layout
        }.distinctUntilChanged()

        val cellsPipeline = layoutFlow.flatMapLatest { layout ->
            // Hidden helper streams (subscribed but never rendered, so only [ids] grows):
            // CADENCE colouring needs the live power reading for its under-gear rule, and the
            // live POWER/CADENCE fields need their workout target streams to render
            // "value/target" mid-workout even when no target field is on a page. The target
            // streams are Idle outside a workout, so the extra subscriptions cost nothing.
            val displayed = layout.pages.asSequence().flatten().distinct().toList()
            // Synthetic radar fields and the trajectory page marker aren't Karoo streams (radar cells
            // are injected from [nextClimbFlow]; the trajectory draws from [trajectoryFlow]) — keep
            // them out of the subscription set.
            val streamIds = displayed.filterNot { RouteRadar.isSynthetic(it) || it == RouteTrajectory.PAGE_MARKER }
            val ids = buildList {
                addAll(streamIds)
                if (DataType.Type.CADENCE in streamIds && DataType.Type.POWER !in streamIds) add(DataType.Type.POWER)
                if (DataType.Type.POWER in streamIds && DataType.Type.WORKOUT_POWER_TARGET !in streamIds) add(DataType.Type.WORKOUT_POWER_TARGET)
                if (DataType.Type.CADENCE in streamIds && DataType.Type.WORKOUT_CADENCE_TARGET !in streamIds) add(DataType.Type.WORKOUT_CADENCE_TARGET)
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
                        acc + (id to FieldFormat.format(id, state, cfg.imperial, zones, ctx, gear, extLabels))
                    }
            }
            // Fold in the live next-climb radar cells (synthetic ids) and the trajectory map data so
            // both ride the same sample() throttle as the stream cells. Radar cells are harmless on
            // non-radar pages (their ids never look these up); the trajectory is attached to the
            // snapshot only when its page exists (see the snapshot builder).
            cellsFlow.combine(nextClimbFlow) { cells, climb ->
                cells + FieldFormat.radarCells(climb, configFlow.value.imperial)
            }.combine(trajectoryFlow) { cells, traj ->
                Triple(layout, cells, traj)
            }
        }

        // HUD refresh interval: the configured rate, slowed as the glasses battery drains so the
        // low-battery tiers (BatteryWarn) cut the BLE/redraw load that drains it faster, and slowed
        // further while battery-saver is engaged (the slower of the battery-warn tier and the saver
        // floor). A change re-subscribes the streams (same as a layout swap), so it only churns on a
        // config change, a battery threshold crossing, or a saver flip — all rare. (This also makes
        // the configured refresh interval take effect live, superseding the earlier captured-once read.)
        val refreshFlow = combine(
            configFlow, maverick.glassesBattery, Eco.active, Eco.critical,
        ) { cfg, battery, saver, critical ->
            val base = BatteryWarn.forLevel(battery)?.pollMs ?: cfg.refreshIntervalMs
            when {
                critical -> maxOf(base, SaverTuning.CRITICAL_REFRESH_MS)
                saver -> maxOf(base, SaverTuning.SAVER_REFRESH_MS)
                else -> base
            }
        }.distinctUntilChanged()

        refreshFlow.flatMapLatest { intervalMs -> cellsPipeline.sample(intervalMs) }
            .combine(rideStateFlow) { (layout, cells, traj), ride ->
                HudSnapshot(
                    pages = layout.pages.map { page -> page.map { id -> cells[id] ?: HudCell.blank(id) } },
                    paused = ride is RideState.Paused,
                    recording = ride is RideState.Recording,
                    pageIndex = 0,
                    pinnedPage = layout.pinnedPage,
                    rows = configFlow.value.rows,
                    clock = if (configFlow.value.showClock) currentClock() else "",
                    showIcons = configFlow.value.showIcons,
                    // battery (glasses %) is stamped by MaverickBridge, which owns the Evs link.
                    // Trajectory rides along only when its page exists in this layout.
                    trajectory = layout.trajectoryPageIndex?.let { traj },
                    trajectoryPageIndex = layout.trajectoryPageIndex,
                )
            }
            .onEach { snapshot -> maverick.update(snapshot) }
            .launchIn(scope)
    }

    /**
     * A computed page layout: the pages to show, an optional page to pin (segment/climb/radar/
     * descent-trajectory), and the index of the trajectory map page (which the glasses draw as
     * graphics rather than cells), if present.
     */
    private data class Layout(
        val pages: List<List<String>>,
        val pinnedPage: Int?,
        val trajectoryPageIndex: Int? = null,
    )

    /** The loaded route: climbs + total length (radar) and the decoded path geometry (trajectory map). */
    private data class RouteInfo(
        val climbs: List<OnNavigationState.NavigationState.Climb>,
        val routeDistance: Double,
        val points: List<LatLng>,
        val reversed: Boolean,
    )

    /**
     * Which auto-page signals currently apply. Pin precedence (segment > climb > radar >
     * descent-trajectory) and the trajectory-page append are applied in the layout builder.
     */
    private data class AutoPages(
        val segment: Boolean = false,
        val climb: Boolean = false,
        val radar: Boolean = false,
        val trajAvailable: Boolean = false,
        val descent: Boolean = false,
    )

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
