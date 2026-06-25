package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.settings.HudConfig
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.random.Random

/**
 * Builds a [HudSnapshot] from a [HudConfig] using fabricated demo sensor values, so the settings
 * app can mirror "what the rider would see" onto the glasses live while they edit. Runs each demo
 * value through the real [FieldFormat] so units, zone colours and icons match the actual HUD.
 */
object HudPreviewBuilder {

    /**
     * A preview snapshot for [cfg]. [seed] jitters the live-ish values so the glasses feel alive;
     * [pageIndex] lets the caller cycle pages on the configured timer.
     */
    fun snapshot(cfg: HudConfig, seed: Int, pageIndex: Int): HudSnapshot {
        val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
        val gear = GearLayout(cfg.gear.front, cfg.gear.rear, cfg.gear.display)
        val cap = cellsForRows(cfg.rows)
        val pages = cfg.pages.map { page ->
            val ids = page.take(cap)
            // Mirror the runtime overlay: a page that carries a workout-target field gets a context
            // pre-stashed with the demo targets, so its live POWER/CADENCE tiles preview the same
            // "current/target" composite (with range colouring) the rider sees mid-workout. Pages
            // without a target field keep a null context and preview as plain zone-coloured values.
            val ctx = workoutPreviewCtx(ids, cfg, zones, gear, seed)
            ids.map { id -> FieldFormat.format(id, demoState(id, cfg, seed), cfg.imperial, zones, ctx, gear) }
        }.filter { it.isNotEmpty() }
        val idx = if (pages.isEmpty()) 0 else pageIndex % pages.size
        return HudSnapshot(
            pages = pages,
            paused = false,
            recording = true, // so the screen renders the cells rather than "waiting for ride"
            pageIndex = idx,
            rows = cfg.rows,
            clock = "", // stamped by the bridge if the clock is enabled
            showIcons = cfg.showIcons,
            fontSize = cfg.hudFontSize,
        )
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
