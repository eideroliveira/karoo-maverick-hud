package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.settings.HudConfig
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Builds a [HudSnapshot] from a [HudConfig] using fabricated demo sensor values, so the settings
 * app can mirror "what the rider would see" onto the glasses live while they edit. Runs each demo
 * value through the real [FieldFormat] so units, zone colours and icons match the actual HUD.
 */
object HudPreviewBuilder {

    /**
     * A preview snapshot for [cfg] at cycle position [sceneIndex]. The mirror tours every layout the
     * glasses raise on a ride — the rider's numbered pages, then the on-climb summary, the next-climb
     * radar and the descent trajectory (the latter two when enabled) drawn as CENTRE OVERLAYS over the
     * first page — mirroring `previewScenes()` in the on-screen lens so both previews agree. [seed]
     * jitters the live-ish values so the glasses feel alive.
     */
    fun snapshot(cfg: HudConfig, seed: Int, sceneIndex: Int): HudSnapshot {
        val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
        val gear = GearLayout(cfg.gear.front, cfg.gear.rear, cfg.gear.display)
        val cap = cellsForRows(cfg.rows)

        fun renderPage(ids: List<String>): List<HudCell> {
            val pageIds = ids.take(cap)
            // Mirror the runtime overlay: a page that carries a workout-target field gets a context
            // pre-stashed with the demo targets, so its live POWER/CADENCE tiles preview the same
            // "current/target" composite (with range colouring) the rider sees mid-workout.
            val ctx = workoutPreviewCtx(pageIds, cfg, zones, gear, seed)
            return pageIds.map { id ->
                // The block·rep field is synthetic (no stream) — render its preview cell directly.
                if (WorkoutBlocks.isSynthetic(id)) WorkoutBlocks.previewCell()
                else FieldFormat.format(id, demoState(id, cfg, seed), cfg.imperial, zones, ctx, gear)
            }
        }

        val numbered = cfg.pages.map { renderPage(it) }.filter { it.isNotEmpty() }
        // The page the centre overlays draw over (the rider's first page, as on a real ride).
        val base = numbered.firstOrNull()
            ?: renderPage(cfg.climbPage).ifEmpty { listOf(HudCell("--", "", HudColor.WHITE)) }

        // Scene list, in the same order as the on-screen lens previewScenes(): numbered pages, then
        // the on-climb summary, the next-climb radar and the descent trajectory (last two when on).
        val scenes: List<HudSnapshot> = buildList {
            numbered.forEach { add(baseSnapshot(cfg, it)) }
            add(baseSnapshot(cfg, base).copy(climb = demoClimb(cfg)))
            if (cfg.radarEnabled) add(baseSnapshot(cfg, base).copy(radar = demoRadar(cfg)))
            if (cfg.trajectoryEnabled) add(baseSnapshot(cfg, base).copy(trajectory = demoTrajectory(cfg)))
        }
        return scenes[sceneIndex % scenes.size] // scenes always holds at least the climb scene
    }

    /** A single-page snapshot (no overlay) for [cells], with the shared ride-state defaults. */
    private fun baseSnapshot(cfg: HudConfig, cells: List<HudCell>): HudSnapshot = HudSnapshot(
        pages = listOf(cells),
        paused = false,
        recording = true, // so the screen renders the cells rather than "waiting for ride"
        pageIndex = 0,
        rows = cfg.rows,
        clock = "", // stamped by the bridge if the clock is enabled
        showIcons = cfg.showIcons,
        fontSize = cfg.hudFontSize,
    )

    /** Demo next-climb radar overlay (~0.8 km to an 8% ramp) for both previews. */
    fun demoRadar(cfg: HudConfig): RadarOverlay? = FieldFormat.radarOverlay(
        NextClimb(distanceToStart = 800.0, etaSeconds = 72.0, grade = 8.0, length = 1200.0, totalElevation = 96.0),
        cfg.imperial,
    )

    /** Demo on-climb overlay (mid-climb, ~150 m of ascent left, grade-coloured profile) for both previews. */
    fun demoClimb(cfg: HudConfig): ClimbOverlay? {
        val climbState = StreamState.Streaming(
            DataPoint(
                dataTypeId = DataType.Type.CLIMB,
                values = mapOf(
                    DataType.Field.DISTANCE_TO_TOP to 1_800.0, // horizontal m to the end
                    DataType.Field.ELEVATION_TO_TOP to 150.0,  // vertical m of ascent left
                    DataType.Field.CLIMB_NUMBER to 2.0,
                    DataType.Field.TOTAL_CLIMBS to 3.0,
                ),
            ),
        )
        val gradeState = StreamState.Streaming(
            DataPoint(dataTypeId = DataType.Type.ELEVATION_GRADE, values = mapOf(DataType.Field.ELEVATION_GRADE to 8.5)),
        )
        return FieldFormat.climbOverlay(climbState, gradeState, mpaWatts = cfg.ftp * 1.6, profile = demoClimbProfile(), imperial = cfg.imperial)
    }

    /** A rising, grade-coloured silhouette for the demo climb profile. */
    private fun demoClimbProfile(): ClimbProfileData {
        val buckets = ClimbProfile.BUCKETS
        val bars = (0 until buckets).map { b ->
            val f0 = b.toFloat() / buckets
            val f1 = (b + 1f) / buckets
            val height = (0.12f + 0.85f * f1).coerceIn(0.06f, 1f)   // rises toward the summit
            val grade = 3.0 + 7.0 * sin(f0 * Math.PI)               // eases low, bites mid → varied colours
            ProfileBar(startFrac = f0, endFrac = f1, heightFrac = height, color = ClimbProfile.gradeColor(grade))
        }
        return ClimbProfileData(bars, progressFrac = 0.4f)
    }

    /** Demo heading-up trajectory (a curving road ahead) for the descent preview. */
    fun demoTrajectory(cfg: HudConfig): Trajectory {
        val pts = (0..20).map { i ->
            val f = i / 20f
            val forward = f * 190f
            val right = (sin(f * 3.4) * 26.0 * (0.3 + 0.7 * f)).toFloat()
            MetersPoint(right, forward)
        }
        val speed = if (cfg.imperial) HudCell("20", "mph", HudColor.WHITE) else HudCell("32", "km/h", HudColor.WHITE)
        val grade = HudCell("-6", "%", HudColor.WHITE)
        return Trajectory(points = pts, overlay = listOf(speed, grade))
    }

    /**
     * A [FormatContext] pre-stashed with the demo workout targets when [ids] contains a workout
     * target field, else null. Formatting a target field stashes its target into the context (the
     * same side effect the runtime pipeline relies on for the live POWER/CADENCE overlay); doing it
     * up front means the order of fields on the page doesn't matter — POWER/CADENCE pick up the
     * stashed target regardless of where they sit.
     */
    private fun workoutPreviewCtx(ids: List<String>, cfg: HudConfig, zones: ZoneConfig, gear: GearLayout, seed: Int): FormatContext? {
        val targetIds = ids.filter {
            it == DataType.Type.WORKOUT_POWER_TARGET || it == DataType.Type.WORKOUT_CADENCE_TARGET
        }
        if (targetIds.isEmpty()) return null
        val ctx = FormatContext()
        targetIds.forEach { id -> FieldFormat.format(id, demoState(id, cfg, seed), cfg.imperial, zones, ctx, gear) }
        return ctx
    }

    /** A fabricated [StreamState] carrying a plausible demo value for [dataTypeId]. */
    private fun demoState(dataTypeId: String, cfg: HudConfig, seed: Int): StreamState {
        val rng = Random(seed * 31 + dataTypeId.hashCode())
        fun jit() = rng.nextDouble() // 0..1
        val values: Map<String, Double> = when (dataTypeId) {
            DataType.Type.POWER -> mapOf(DataType.Field.POWER to (cfg.ftp * (0.70 + jit() * 0.40)))
            DataType.Type.CADENCE -> mapOf(DataType.Field.CADENCE to (cfg.idealCadence + (jit() - 0.5) * 22))
            DataType.Type.HEART_RATE -> mapOf(DataType.Field.HEART_RATE to (cfg.maxHr * (0.64 + jit() * 0.30)))
            DataType.Type.SPEED -> mapOf(DataType.Field.SPEED to ((30.0 + jit() * 8.0) / 3.6))
            DataType.Type.AVERAGE_SPEED -> mapOf(DataType.Field.AVERAGE_SPEED to (29.6 / 3.6))
            DataType.Type.DISTANCE -> mapOf(DataType.Field.DISTANCE to 42_100.0)
            DataType.Type.ELAPSED_TIME -> mapOf(DataType.Field.ELAPSED_TIME to 5_070_000.0) // 1:24:30 in ms
            DataType.Type.PEDAL_POWER_BALANCE -> mapOf(DataType.Field.PEDAL_POWER_BALANCE_LEFT to 51.0)
            DataType.Type.SHIFTING_GEARS -> mapOf(
                DataType.Field.SHIFTING_FRONT_GEAR_TEETH to cfg.gear.front.last().toDouble(),
                DataType.Field.SHIFTING_REAR_GEAR_TEETH to cfg.gear.rear[cfg.gear.rear.size / 2].toDouble(),
            )
            DataType.Type.WORKOUT_POWER_TARGET -> mapOf(
                DataType.Field.WORKOUT_TARGET_VALUE to cfg.ftp * 0.90,
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to cfg.ftp * 0.85,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to cfg.ftp * 0.95,
            )
            DataType.Type.WORKOUT_CADENCE_TARGET -> mapOf(DataType.Field.WORKOUT_TARGET_VALUE to cfg.idealCadence.toDouble())
            DataType.Type.WORKOUT_INTERVAL_COUNT -> mapOf(
                DataType.Field.WORKOUT_CURRENT_STEP to 3.0,
                DataType.Field.WORKOUT_STEP_COUNT to 12.0,
            )
            DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION ->
                mapOf(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to 83_000.0) // ms → "1:23"
            // Strava live segment
            DataType.Type.SEGMENT_TIME_TO_PR -> mapOf(DataType.Field.SEGMENT_PR_DELTA_TIME to -5_000.0) // 5s ahead
            DataType.Type.SEGMENT_TIME_TO_KOM -> mapOf(DataType.Field.SEGMENT_KOM_DELTA_TIME to 12_000.0)
            DataType.Type.SEGMENT_TIME -> mapOf(DataType.Field.SEGMENT_TIME_ELAPSED to 95_000.0) // ms → "1:35"
            DataType.Type.SEGMENT_PR -> mapOf(DataType.Field.SEGMENT_PR_TIME to 100_000.0)
            DataType.Type.SEGMENT_DISTANCE_REMAINING -> mapOf(DataType.Field.SEGMENT_DISTANCE_REMAINING to 600.0)
            DataType.Type.SEGMENT_ELEVATION_REMAINING -> mapOf(DataType.Field.SEGMENT_ELEVATION_REMAINING to 45.0)
            // Climb
            DataType.Type.ELEVATION_GRADE -> mapOf(DataType.Field.ELEVATION_GRADE to (5.0 + jit() * 6.0))
            DataType.Type.VERTICAL_SPEED -> mapOf(DataType.Field.VERTICAL_SPEED to (900.0 + jit() * 400.0))
            DataType.Type.DISTANCE_TO_TOP -> mapOf(DataType.Field.DISTANCE_TO_TOP to 1_800.0)
            DataType.Type.ELEVATION_TO_TOP -> mapOf(DataType.Field.ELEVATION_TO_TOP to 210.0)
            DataType.Type.CLIMB_NUMBER -> mapOf(DataType.Field.CLIMB_NUMBER to 2.0, DataType.Field.TOTAL_CLIMBS to 3.0)
            else -> mapOf(dataTypeId to jit() * 100.0)
        }
        return StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = values))
    }
}
