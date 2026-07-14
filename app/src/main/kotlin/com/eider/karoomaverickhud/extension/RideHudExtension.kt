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
import com.eider.karoomaverickhud.settings.raceBasePages
import com.eider.karoomaverickhud.settings.SettingsActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
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
        // Used to hand off from the next-climb preview to the on-climb overlay (below); no longer
        // pins a page.
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
                    RouteInfo(
                        climbs = it.climbs,
                        routeDistance = it.routeDistance,
                        points = RouteTrajectory.decode(it.routePolyline),
                        elevation = ClimbProfile.decodeElevation(it.routeElevationPolyline),
                        reversed = it.reversed,
                    )
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

        // The heading-up trajectory to draw in the centre of the current page, gated by a debounced,
        // hysteretic descent decision ([DescentGate]) so it neither flickers as the grade hovers nor
        // vanishes the instant a descent eases: it shows after ~3 s at ≤ -2%, hides after ~3 s once
        // the grade rises past -1%, and hides at once on a flat/positive grade. Only projects — and
        // only churns the snapshot — while shown. Carries a small speed+grade overlay for the footer.
        val trajectoryFlow = combine(
            configFlow.map { it.trajectoryEnabled }.distinctUntilChanged(),
            routeFlow, locationFlow, gradeFlow, trajSpeedFlow,
        ) { enabled, route, loc, gradeState, speedState ->
            TrajInputs(enabled, route, loc, gradeState, speedState)
        }.scan(DescentGate.INITIAL to (null as Trajectory?)) { (gate, _), inp ->
            val next = gate.advance(FieldFormat.gradeOf(inp.gradeState), System.currentTimeMillis())
            val traj = if (!inp.enabled || !next.shown || inp.route == null || inp.loc == null) {
                null
            } else {
                val pts = RouteTrajectory.project(
                    inp.route.points, LatLng(inp.loc.lat, inp.loc.lng), inp.loc.orientation, inp.route.reversed,
                )
                if (pts.size < 2) {
                    null
                } else {
                    val cfg = configFlow.value
                    val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
                    Trajectory(
                        pts,
                        listOf(
                            FieldFormat.format(DataType.Type.SPEED, inp.speedState, cfg.imperial, zones),
                            FieldFormat.format(DataType.Type.ELEVATION_GRADE, inp.gradeState, cfg.imperial, zones),
                        ),
                    )
                }
            }
            next to traj
        }.map { it.second }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

        // The climb the rider is approaching, or null when none is within the look-ahead window
        // (~45 s out, capped at 1 km). Gated on the radar toggle so its streams cost nothing when
        // off, and suppressed once on the climb so the on-climb overlay takes over cleanly. Feeds the
        // centre radar overlay (via [FieldFormat.radarOverlay] in the pipeline tail).
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

        // MPA (Maximal Power Available, a Xert-style extension field) for the on-climb overlay,
        // discovered once by label. Null id when no MPA extension is installed — the overlay then
        // just omits the MPA slot. The stream itself is subscribed only inside the radar-gated branch
        // below, so it costs nothing while the feature is off. Watts come from the field's single value.
        val mpaId = KarooDataTypeCatalog.mpaDataTypeId(applicationContext)

        // The on-climb centre overlay (replaces the old pinned climb page): a live summary —
        // MPA, vertical-to-summit, horizontal-to-end, current grade over the average grade still to
        // climb — plus a grade-coloured elevation silhouette sliced from the route's elevation
        // profile. Gated on the same radar toggle as the next-climb preview (radar = approaching +
        // on-climb), so the two are one switch and none of these streams run while it's off. Null off
        // a climb. Feeds the centre overlay.
        val onClimbFlow = configFlow.map { it.radarEnabled }.distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(null)
                } else {
                    val mpaSource = if (mpaId == null) {
                        flowOf<Double?>(null)
                    } else {
                        karoo.streamDataFlow(mpaId).map { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
                    }
                    combine(
                        routeFlow,
                        karoo.streamDataFlow(DataType.Type.CLIMB),
                        gradeFlow,
                        karoo.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION),
                        mpaSource,
                    ) { route, climbState, gradeState, dtd, mpa ->
                        val progress = route?.let {
                            RouteRadar.routeProgress(it.routeDistance, RouteRadar.distanceToDestination(dtd))
                        }
                        val active = ClimbProfile.activeClimb(route?.climbs.orEmpty(), progress)
                        val profile = ClimbProfile.build(route?.elevation.orEmpty(), active, progress)
                        FieldFormat.climbOverlay(climbState, gradeState, mpa, profile, configFlow.value.imperial)
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // Mid-workout centre overlay: the interval countdown plus avg/NP power, drawn on every page
        // except the workout page itself (the renderer suppresses it there via
        // [HudSnapshot.workoutPageIndex]). Gated on workout-active so its streams cost nothing
        // otherwise, and dropped in critical battery mode — its per-second countdown would defeat
        // the frame dedup that mode leans on.
        val workoutOverlayFlow = combine(workoutActiveFlow, Eco.critical) { active, critical -> active && !critical }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(null)
                } else {
                    combine(
                        karoo.streamDataFlow(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION),
                        // Lap avg / NP (not ride-wide) so the readout tracks the current interval —
                        // a manual lap is taken at each interval so this is the effort in progress.
                        karoo.streamDataFlow(DataType.Type.POWER_LAP),
                        karoo.streamDataFlow(DataType.Type.NORMALIZED_POWER_LAP),
                    ) { remain, avg, np ->
                        val cfg = configFlow.value
                        val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
                        FieldFormat.workoutOverlay(remain, avg, np, zones)
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // Gear-change flash: whenever the resolved gear changes, show the new chainring/cassette
        // ratio in the centre for [GearShift.VISIBLE_MS], colour-coded by how the ratio moved (see
        // [GearShift]). The SHIFTING_GEARS stream is Idle without a shifting sensor, so this costs
        // nothing then; dropped in critical battery mode alongside the other centre overlays. Re-
        // subscribed when the gear config changes so teeth resolution uses the fresh drivetrain.
        val gearShiftFlow = combine(
            configFlow.map { it.gear }.distinctUntilChanged(), Eco.critical,
        ) { gear, critical -> gear to critical }
            .flatMapLatest { (gearCfg, critical) ->
                if (critical) {
                    flowOf(null)
                } else {
                    val layout = GearLayout(gearCfg.front, gearCfg.rear, gearCfg.display)
                    karoo.streamDataFlow(DataType.Type.SHIFTING_GEARS)
                        .map { GearShift.teeth((it as? StreamState.Streaming)?.dataPoint, layout) }
                        .filterNotNull()
                        .distinctUntilChanged()
                        // Carry the previous gear so each change can be compared; the initial fold
                        // value and the first real reading both yield null (no flash at ride start).
                        .scan<Pair<Int, Int>, Pair<Pair<Int, Int>?, GearShiftOverlay?>>(null to null) { (prev, _), next ->
                            next to prev?.let { GearShift.overlay(it, next) }
                        }
                        .mapNotNull { it.second }
                        // Show the flash, then clear it after the window. flatMapLatest restarts the
                        // timer on a fresh shift, so rapid shifting keeps the latest ratio up.
                        .flatMapLatest { overlay ->
                            flow<GearShiftOverlay?> {
                                emit(overlay)
                                delay(GearShift.VISIBLE_MS)
                                emit(null)
                            }
                        }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

        // The only auto-page gate that still creates a pinned page is a live Strava segment. The
        // next-climb radar, the on-climb summary/profile and the descent trajectory all now draw as
        // centre overlays on whatever page is shown (see the snapshot builder), so they no longer add
        // or pin pages here. (climbActiveFlow is still used above to suppress the next-climb preview
        // once the rider is actually on the climb.)
        val autoPagesFlow = segmentActiveFlow
            .map { AutoPages(segment = it) }
            .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, AutoPages())

        // Display labels for any extension-provided fields the rider added to a page (MPA, time to
        // summit, …) so the generic renderer can unit-label them. Discovered once at start.
        val extLabels = KarooDataTypeCatalog.labels(applicationContext)

        // The fields to render, as pages of data-type ids, capped to the cells the chosen row count
        // exposes. Race mode cycles only the race-flagged pages; otherwise the user's custom pages.
        // A live workout prepends the configured workout page; a live Strava segment prepends its
        // page and pins the display to it. The next-climb radar, the on-climb summary/profile and the
        // descent trajectory are centre overlays (see the snapshot builder), not pages.
        val layoutFlow = combine(
            configFlow, workoutActiveFlow, autoPagesFlow,
        ) { cfg, workoutActive, auto ->
            val cap = cellsForRows(cfg.rows)
            val base = if (cfg.raceMode) {
                // Race mode: only the race-flagged pages cycle (the dynamic auto-pages still pin on top).
                raceBasePages(cfg.pages, cfg.racePages, cap)
            } else {
                cfg.pages.asSequence().map { it.take(cap) }.filter { it.isNotEmpty() }.toList()
            }
            val priority = mutableListOf<List<String>>()
            var pinned: Int? = null
            var workoutPage: Int? = null
            if (workoutActive && cfg.workoutPage.isNotEmpty()) {
                workoutPage = priority.size
                priority.add(cfg.workoutPage.take(cap))
            }
            if (auto.segment && cfg.segmentPage.isNotEmpty()) {
                pinned = priority.size
                priority.add(cfg.segmentPage.take(cap))
            }
            Layout(priority + base, pinned, workoutPage)
        }.combine(Eco.critical) { layout, critical ->
            // Critical battery: collapse to one minimal page (speed + time) to squeeze out the final
            // minutes. Overrides every priority/pinned page; the streams it needs are subscribed
            // automatically since [cellsPipeline] derives its ids from the live layout.
            if (critical) Layout(listOf(SaverTuning.MINIMAL_PAGE), pinnedPage = null) else layout
        }.distinctUntilChanged()

        // Block·rep tracker for the synthetic [WorkoutBlocks.FIELD_REP] field — stateful, so it lives
        // in its own persistent flow (the per-layout FormatContext is rebuilt whenever an auto-page
        // appears and would reset the count mid-ride). Gated on the field being configured somewhere;
        // its three workout streams are Idle outside a structured workout, so it stays quiet otherwise.
        val blockRepFlow = configFlow
            .map { cfg ->
                WorkoutBlocks.FIELD_REP in cfg.workoutPage ||
                    WorkoutBlocks.FIELD_REP in cfg.segmentPage ||
                    cfg.pages.any { WorkoutBlocks.FIELD_REP in it }
            }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(null)
                } else {
                    combine(
                        karoo.streamDataFlow(DataType.Type.WORKOUT_INTERVAL_COUNT),
                        karoo.streamDataFlow(DataType.Type.WORKOUT_POWER_TARGET),
                        karoo.streamDataFlow(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION),
                    ) { stepState, targetState, durState ->
                        WorkoutBlocks.observe(stepState, targetState, durState, configFlow.value.ftp)
                    }
                        .scan(WorkoutBlocks.INITIAL) { state, obs -> state.advance(obs) }
                        .map { it.display }
                        .distinctUntilChanged()
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

        val cellsPipeline = layoutFlow.flatMapLatest { layout ->
            // Hidden helper streams (subscribed but never rendered, so only [ids] grows):
            // CADENCE colouring needs the live power reading for its under-gear rule, and the
            // live POWER/CADENCE fields need their workout target streams to render
            // "value/target" mid-workout even when no target field is on a page. The target
            // streams are Idle outside a workout, so the extra subscriptions cost nothing.
            val displayed = layout.pages.asSequence().flatten().distinct().toList()
            // The block·rep field is synthetic (computed in [blockRepFlow], not a Karoo stream) — keep
            // it out of the subscription set.
            val streamIds = displayed.filterNot { WorkoutBlocks.isSynthetic(it) }
            val ids = buildList {
                addAll(streamIds)
                if (DataType.Type.CADENCE in streamIds && DataType.Type.POWER !in streamIds) add(DataType.Type.POWER)
                if (DataType.Type.POWER in streamIds && DataType.Type.WORKOUT_POWER_TARGET !in streamIds) add(DataType.Type.WORKOUT_POWER_TARGET)
                if (DataType.Type.CADENCE in streamIds && DataType.Type.WORKOUT_CADENCE_TARGET !in streamIds) add(DataType.Type.WORKOUT_CADENCE_TARGET)
                // The GRADE field renders "live/avg-remaining" on a climb; the CLIMB stream carries the
                // remaining distance/elevation to the top that the average needs. Idle off-climb, so it
                // costs nothing then.
                if (DataType.Type.ELEVATION_GRADE in streamIds && DataType.Type.CLIMB !in streamIds) add(DataType.Type.CLIMB)
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
            // Fold the block·rep field (synthetic) into the cells, then bundle the centre-overlay data
            // — the descent trajectory, the next-climb radar, the on-climb summary/profile and the
            // mid-workout readout — into one frame so all of it rides the same sample() throttle as
            // the stream cells. (The workout overlay joins via a chained combine only because the
            // typed combine overloads stop at five flows.)
            combine(cellsFlow, blockRepFlow, trajectoryFlow, nextClimbFlow, onClimbFlow) { cells, rep, traj, nextClimb, onClimb ->
                val withRep = if (rep != null) cells + (WorkoutBlocks.FIELD_REP to WorkoutBlocks.cell(rep)) else cells
                Frame(
                    layout = layout,
                    cells = withRep,
                    trajectory = traj,
                    radar = FieldFormat.radarOverlay(nextClimb, configFlow.value.imperial),
                    climb = onClimb,
                )
            }.combine(workoutOverlayFlow) { frame, workout -> frame.copy(workout = workout) }
                .combine(gearShiftFlow) { frame, gearShift -> frame.copy(gearShift = gearShift) }
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
            .combine(rideStateFlow) { frame, ride ->
                HudSnapshot(
                    pages = frame.layout.pages.map { page -> page.map { id -> frame.cells[id] ?: HudCell.blank(id) } },
                    paused = ride is RideState.Paused,
                    recording = ride is RideState.Recording,
                    pageIndex = 0,
                    pinnedPage = frame.layout.pinnedPage,
                    rows = configFlow.value.rows,
                    clock = if (configFlow.value.showClock) currentClock() else "",
                    showIcons = configFlow.value.showIcons,
                    fontSize = configFlow.value.hudFontSize,
                    // battery (glasses %) is stamped by MaverickBridge, which owns the Evs link.
                    // The trajectory / radar / on-climb centre overlays ride along on whatever page is shown.
                    trajectory = frame.trajectory,
                    radar = frame.radar,
                    climb = frame.climb,
                    workout = frame.workout,
                    workoutPageIndex = frame.layout.workoutPageIndex,
                    gearShift = frame.gearShift,
                )
            }
            .onEach { snapshot -> maverick.update(snapshot) }
            .launchIn(scope)
    }

    /**
     * A computed page layout: the pages to show, an optional page to pin (segment/climb), and the
     * index of the live workout page ([workoutPageIndex], null when none) so the workout overlay
     * can be suppressed on it.
     */
    private data class Layout(
        val pages: List<List<String>>,
        val pinnedPage: Int?,
        val workoutPageIndex: Int? = null,
    )

    /**
     * One throttled frame handed to the snapshot builder: the page [layout] + resolved [cells], plus
     * the centre overlays — the descent [trajectory], the next-climb [radar], the on-[climb]
     * summary/profile and the mid-[workout] readout — each null when not active.
     */
    private data class Frame(
        val layout: Layout,
        val cells: Map<String, HudCell>,
        val trajectory: Trajectory?,
        val radar: RadarOverlay?,
        val climb: ClimbOverlay?,
        val workout: WorkoutOverlay? = null,
        val gearShift: GearShiftOverlay? = null,
    )

    /**
     * The loaded route: climbs + total length (radar / on-climb match), the decoded path geometry
     * (trajectory map), and the decoded distance/elevation profile (the on-climb silhouette).
     */
    private data class RouteInfo(
        val climbs: List<OnNavigationState.NavigationState.Climb>,
        val routeDistance: Double,
        val points: List<LatLng>,
        val elevation: List<ElevSample>,
        val reversed: Boolean,
    )

    /** Per-tick inputs to the trajectory's descent gate + projection (combined before the gate scan). */
    private data class TrajInputs(
        val enabled: Boolean,
        val route: RouteInfo?,
        val loc: OnLocationChanged?,
        val gradeState: StreamState,
        val speedState: StreamState,
    )

    /**
     * Which page-creating auto-page signal currently applies — only a live Strava segment now. The
     * next-climb radar, the on-climb summary/profile and the descent trajectory are centre overlays,
     * not pages, so they're not tracked here.
     */
    private data class AutoPages(
        val segment: Boolean = false,
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
