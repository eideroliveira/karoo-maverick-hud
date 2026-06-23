package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** The HUD draws two edge columns (first & last of a notional four), so the center stays clear. */
const val COLUMNS = 2

/** Rows are user-selectable; the layout supports two or three. */
const val MIN_ROWS = 2
const val MAX_ROWS = 3

/** Absolute cell cap (two columns × three rows) — used to size the screen's element pool. */
const val MAX_CELLS = COLUMNS * MAX_ROWS

/** Cells shown for a given row count, clamped to the supported range. */
fun cellsForRows(rows: Int): Int = COLUMNS * rows.coerceIn(MIN_ROWS, MAX_ROWS)

/**
 * Value color, mapped to an EvsColor by the screen. The full palette the renderer understands;
 * which hues a given field actually uses is decided by the zone maps below. Non-training fields
 * and missing data stay [WHITE] (neutral — the color only "lights up" for effort).
 */
enum class HudColor { WHITE, GREEN, YELLOW, ORANGE, RED, PURPLE, CYAN }

/**
 * Low-battery warning tiers for the glasses. As the glasses battery drains we slow the HUD refresh
 * (to cut the BLE/redraw load that drains it faster) and surface the percentage in a warning colour
 * at the top of the screen. Entries are ordered most-severe first so [forLevel] can take the first
 * match; [pollMs] is the HUD refresh interval to use while the tier is active.
 */
enum class BatteryWarn(val maxPct: Int, val pollMs: Long, val color: HudColor) {
    CRITICAL(maxPct = 10, pollMs = 5_000L, color = HudColor.RED),
    LOW(maxPct = 20, pollMs = 3_000L, color = HudColor.ORANGE),
    WARN(maxPct = 30, pollMs = 2_000L, color = HudColor.YELLOW);

    companion object {
        /** Severest tier whose threshold the battery is at or below; null when healthy or unknown. */
        fun forLevel(batteryPct: Int?): BatteryWarn? {
            if (batteryPct == null) return null
            return entries.firstOrNull { batteryPct <= it.maxPct }
        }
    }
}

/**
 * One editable training-zone band, as a percentage window of a base value (FTP or MaxHR).
 * [lo]/[hi] are inclusive-low, exclusive-high percentages; [name]/[sub] are display-only.
 * Serializable so it round-trips through [com.eider.karoomaverickhud.settings.HudPreferences].
 */
@Serializable
data class ZoneBand(val name: String, val sub: String, val lo: Int, val hi: Int)

/**
 * Power zones: the full 7-band model (cyan Z0 → purple Z6), named for their common meanings.
 * Boundaries are % of FTP; the rider can edit them in settings, but the count (and so the color
 * mapping below) is fixed at seven.
 */
val DEFAULT_POWER_ZONES: List<ZoneBand> = listOf(
    ZoneBand("Z0", "Leisure", 0, 45),
    ZoneBand("Z1", "Recovery", 45, 55),
    ZoneBand("Z2", "Endurance", 55, 75),
    ZoneBand("Z3", "Tempo", 75, 90),
    ZoneBand("Z4", "Threshold", 90, 105),
    ZoneBand("Z5", "VO2 Max", 105, 120),
    ZoneBand("Z6", "Anaerobic", 120, 150),
)

/** HR zones: classic 5-color Coggan bands (no Z0). Boundaries are % of MaxHR. */
val DEFAULT_HR_ZONES: List<ZoneBand> = listOf(
    ZoneBand("Z1", "Recovery", 0, 60),
    ZoneBand("Z2", "Aerobic", 60, 70),
    ZoneBand("Z3", "Tempo", 70, 80),
    ZoneBand("Z4", "Threshold", 80, 90),
    ZoneBand("Z5", "Max", 90, 110),
)

/** Color per zone index — kept in lockstep with the band lists' fixed lengths. */
val POWER_ZONE_COLORS = listOf(
    HudColor.CYAN, HudColor.WHITE, HudColor.GREEN, HudColor.YELLOW, HudColor.ORANGE, HudColor.RED, HudColor.PURPLE,
)
val HR_ZONE_COLORS = listOf(
    HudColor.WHITE, HudColor.GREEN, HudColor.ORANGE, HudColor.RED, HudColor.PURPLE,
)

/** The band whose [ZoneBand.lo] the percentage clears, searched top-down (the design's rule). */
fun zoneIndexByPct(zones: List<ZoneBand>, pct: Double): Int {
    for (i in zones.indices.reversed()) if (pct >= zones[i].lo) return i
    return 0
}

/**
 * The rider's configured drivetrain, used to resolve teeth from a reported gear *position* when
 * the shifting sensor doesn't report teeth directly. [front]/[rear] are tooth counts (chainrings,
 * cassette cogs). [display] is the HUD readout style: "teeth" (50/14), "ratio" (3.57), "inches".
 */
data class GearLayout(
    val front: List<Int> = emptyList(),
    val rear: List<Int> = emptyList(),
    val display: String = "teeth",
)

/**
 * Zone thresholds from the user's profile; 0 base disables coloring for that field. Carries the
 * editable band lists so the renderer and the settings preview color identically.
 */
data class ZoneConfig(
    val ftp: Int,
    val maxHr: Int,
    val idealCadence: Int,
    val powerZones: List<ZoneBand> = DEFAULT_POWER_ZONES,
    val hrZones: List<ZoneBand> = DEFAULT_HR_ZONES,
) {
    companion object {
        val NONE = ZoneConfig(0, 0, 0)
    }
}

/**
 * Stream-local cross-field state that cadence coloring needs: the last power reading
 * (since under-gearing depends on `power >= Z3`) and the timestamp when the under-gear
 * condition first became true (since it has to hold for ≥3 s before turning red).
 *
 * Lives for the duration of one pipeline (instantiated in [com.eider.karoomaverickhud.extension.RideHudExtension]);
 * mutated only from the single scan coroutine, so no synchronization needed.
 */
class FormatContext {
    var lastPowerWatts: Double? = null
    var underGearedSinceMs: Long? = null

    /**
     * Last workout step targets, stashed whenever a target field is formatted (the pipeline
     * subscribes the target streams alongside live POWER/CADENCE). While a target streams
     * (i.e. a structured workout step prescribes one), the live fields render "value/target"
     * and switch from zone coloring to range-vs-target coloring.
     */
    var powerTarget: WorkoutTarget? = null
    var cadenceTarget: WorkoutTarget? = null

    /** Wall-clock; abstracted so tests can drive time without `System.currentTimeMillis()`. */
    var nowMs: () -> Long = { System.currentTimeMillis() }
}

/** A workout step target — the prescribed value plus its optional min/max band. */
data class WorkoutTarget(val value: Double?, val min: Double?, val max: Double?) {
    /**
     * Whether the stream actually carried a target — it goes Idle outside a workout, and
     * free-ride steps prescribe nothing (the fields read 0), so zeros don't count.
     */
    val isSet: Boolean get() = (value != null && value > 0) || (min != null && max != null && max > 0)

    /** The number to display as the target: the prescribed value, else the band's midpoint. */
    val displayValue: Int? get() = (value ?: if (min != null && max != null) (min + max) / 2 else null)?.roundToInt()
}

/**
 * How a field's value is read & formatted. Drives unit, conversion and zone coloring.
 * DELTA_TIME is a signed time (Strava ±vs PR/KOM, green ahead / red behind); GRADE is a one-decimal
 * percent; CLIMB_STEPS renders climb number as "current/total".
 */
enum class FieldKind { POWER, HR, CADENCE, SPEED, DISTANCE, TIME, INTERVAL_TIME, BALANCE, GEARS, RATIO, NUMBER, STEPS, DELTA_TIME, GRADE, CLIMB_STEPS }

/**
 * A field the HUD knows how to render. [id] is the Karoo [DataType.Type]. [label] is the picker
 * name. [kind] drives formatting/color. [zone] enables training-zone coloring (power/HR).
 * [unit] overrides the HUD unit text outright (e.g. "NP", "ride", "lap"); otherwise the unit is
 * the kind's base ("W", "bpm", "km/h"…) with [suffix] appended ("avg", "lap", "LL"). [valueField]
 * names the [DataType.Field] to read; null reads the point's single value.
 */
data class FieldSpec(
    val id: String,
    val label: String,
    val kind: FieldKind,
    val icon: HudIcon? = null,
    val zone: Boolean = false,
    val unit: String? = null,
    val suffix: String = "",
    val valueField: String? = null,
)

/** Small glyph drawn beside a value when the rider enables HUD icons; mapped to an asset by the screen. */
enum class HudIcon { POWER, SPEED, HEART, CADENCE, TIME, DISTANCE, BALANCE }

/**
 * The full field catalog — live metrics plus averages, max, NP/IF/VI, lap & last-lap variants,
 * and time fields named for what they time (ride / lap / last lap / interval). The settings field
 * picker is built from this list (see FieldCatalog), and the renderer dispatches on [FieldSpec.kind].
 */
val FIELD_SPECS: List<FieldSpec> = listOf(
    // ---- live ----
    FieldSpec(DataType.Type.POWER, "POWER", FieldKind.POWER, HudIcon.POWER, zone = true, valueField = DataType.Field.POWER),
    FieldSpec(DataType.Type.CADENCE, "CAD", FieldKind.CADENCE, HudIcon.CADENCE, valueField = DataType.Field.CADENCE),
    FieldSpec(DataType.Type.HEART_RATE, "HR", FieldKind.HR, HudIcon.HEART, zone = true, valueField = DataType.Field.HEART_RATE),
    FieldSpec(DataType.Type.SPEED, "SPEED", FieldKind.SPEED, HudIcon.SPEED, valueField = DataType.Field.SPEED),
    FieldSpec(DataType.Type.DISTANCE, "DIST", FieldKind.DISTANCE, HudIcon.DISTANCE, valueField = DataType.Field.DISTANCE),
    FieldSpec(DataType.Type.PEDAL_POWER_BALANCE, "L/R %", FieldKind.BALANCE, HudIcon.BALANCE),
    FieldSpec(DataType.Type.SHIFTING_GEARS, "GEAR", FieldKind.GEARS),
    // ---- averages ----
    FieldSpec(DataType.Type.AVERAGE_POWER, "AVG POWER", FieldKind.POWER, HudIcon.POWER, zone = true, suffix = "avg"),
    FieldSpec(DataType.Type.AVERAGE_HR, "AVG HR", FieldKind.HR, HudIcon.HEART, zone = true, suffix = "avg"),
    FieldSpec(DataType.Type.AVERAGE_CADENCE, "AVG CAD", FieldKind.CADENCE, HudIcon.CADENCE, suffix = "avg"),
    FieldSpec(DataType.Type.AVERAGE_SPEED, "AVG SPEED", FieldKind.SPEED, HudIcon.SPEED, suffix = "avg"),
    // ---- max ----
    FieldSpec(DataType.Type.MAX_POWER, "MAX POWER", FieldKind.POWER, HudIcon.POWER, zone = true, suffix = "max"),
    FieldSpec(DataType.Type.MAX_HR, "MAX HR", FieldKind.HR, HudIcon.HEART, zone = true, suffix = "max"),
    FieldSpec(DataType.Type.MAX_SPEED, "MAX SPEED", FieldKind.SPEED, HudIcon.SPEED, suffix = "max"),
    // ---- normalized power / IF / VI ----
    FieldSpec(DataType.Type.NORMALIZED_POWER, "NP", FieldKind.POWER, HudIcon.POWER, zone = true, unit = "NP"),
    FieldSpec(DataType.Type.INTENSITY_FACTOR, "IF", FieldKind.RATIO, unit = "IF"),
    FieldSpec(DataType.Type.VARIABILITY_INDEX, "VI", FieldKind.RATIO, unit = "VI"),
    FieldSpec(DataType.Type.TRAINING_STRESS_SCORE, "TSS", FieldKind.NUMBER, unit = "TSS"),
    // ---- time, named for what they time ----
    FieldSpec(DataType.Type.ELAPSED_TIME, "RIDE TIME", FieldKind.TIME, HudIcon.TIME, unit = "ride", valueField = DataType.Field.ELAPSED_TIME),
    FieldSpec(DataType.Type.ELAPSED_TIME_LAP, "LAP TIME", FieldKind.TIME, HudIcon.TIME, unit = "lap", valueField = DataType.Field.ELAPSED_TIME),
    FieldSpec(DataType.Type.ELAPSED_TIME_LAST_LAP, "LL TIME", FieldKind.TIME, HudIcon.TIME, unit = "last lap", valueField = DataType.Field.ELAPSED_TIME),
    FieldSpec(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION, "INTERVAL", FieldKind.INTERVAL_TIME, HudIcon.TIME, unit = "interval", valueField = DataType.Field.WORKOUT_TIME_TO_STEP_FINISH),
    // ---- this lap ----
    FieldSpec(DataType.Type.DISTANCE_LAP, "LAP DIST", FieldKind.DISTANCE, HudIcon.DISTANCE, suffix = "lap"),
    FieldSpec(DataType.Type.AVERAGE_SPEED_LAP, "LAP SPD", FieldKind.SPEED, HudIcon.SPEED, suffix = "lap"),
    FieldSpec(DataType.Type.NORMALIZED_POWER_LAP, "LAP NP", FieldKind.POWER, HudIcon.POWER, zone = true, unit = "NP lap"),
    FieldSpec(DataType.Type.AVERAGE_LAP_HR, "LAP HR", FieldKind.HR, HudIcon.HEART, zone = true, suffix = "lap"),
    // ---- last lap (LL) ----
    FieldSpec(DataType.Type.DISTANCE_LAP_LAST_LAP, "LL DIST", FieldKind.DISTANCE, HudIcon.DISTANCE, suffix = "LL"),
    FieldSpec(DataType.Type.AVERAGE_SPEED_LAST_LAP, "LL SPD", FieldKind.SPEED, HudIcon.SPEED, suffix = "LL"),
    FieldSpec(DataType.Type.AVERAGE_POWER_LAST_LAP, "LL PWR", FieldKind.POWER, HudIcon.POWER, zone = true, suffix = "LL"),
    FieldSpec(DataType.Type.NORMALIZED_POWER_LAST_LAP, "LL NP", FieldKind.POWER, HudIcon.POWER, zone = true, unit = "NP LL"),
    FieldSpec(DataType.Type.AVERAGE_HR_LAST_LAP, "LL HR", FieldKind.HR, HudIcon.HEART, zone = true, suffix = "LL"),
    FieldSpec(DataType.Type.AVERAGE_CADENCE_LAST_LAP, "LL CAD", FieldKind.CADENCE, HudIcon.CADENCE, suffix = "LL"),
    // ---- workout (targets & step progress; the streams only carry data while a structured
    //      workout is loaded — outside one these render "--") ----
    FieldSpec(DataType.Type.WORKOUT_POWER_TARGET, "PWR TGT", FieldKind.NUMBER, HudIcon.POWER, unit = "W tgt", valueField = DataType.Field.WORKOUT_TARGET_VALUE),
    FieldSpec(DataType.Type.WORKOUT_CADENCE_TARGET, "CAD TGT", FieldKind.NUMBER, HudIcon.CADENCE, unit = "rpm tgt", valueField = DataType.Field.WORKOUT_TARGET_VALUE),
    FieldSpec(DataType.Type.WORKOUT_INTERVAL_COUNT, "STEP", FieldKind.STEPS, HudIcon.TIME, unit = "step"),
    // ---- Strava live segment (shown on the auto segment page while a segment is running) ----
    FieldSpec(DataType.Type.SEGMENT_TIME_TO_PR, "vs PR", FieldKind.DELTA_TIME, HudIcon.TIME, unit = "vs PR", valueField = DataType.Field.SEGMENT_PR_DELTA_TIME),
    FieldSpec(DataType.Type.SEGMENT_TIME_TO_KOM, "vs KOM", FieldKind.DELTA_TIME, HudIcon.TIME, unit = "vs KOM", valueField = DataType.Field.SEGMENT_KOM_DELTA_TIME),
    FieldSpec(DataType.Type.SEGMENT_TIME, "SEG TIME", FieldKind.TIME, HudIcon.TIME, unit = "seg", valueField = DataType.Field.SEGMENT_TIME_ELAPSED),
    FieldSpec(DataType.Type.SEGMENT_PR, "PR", FieldKind.TIME, HudIcon.TIME, unit = "PR", valueField = DataType.Field.SEGMENT_PR_TIME),
    FieldSpec(DataType.Type.SEGMENT_DISTANCE_REMAINING, "SEG DIST", FieldKind.DISTANCE, HudIcon.DISTANCE, unit = "seg left", valueField = DataType.Field.SEGMENT_DISTANCE_REMAINING),
    FieldSpec(DataType.Type.SEGMENT_ELEVATION_REMAINING, "SEG ELEV", FieldKind.NUMBER, HudIcon.DISTANCE, unit = "m seg", valueField = DataType.Field.SEGMENT_ELEVATION_REMAINING),
    // ---- climb (shown on the auto climb page while on a climb, when no segment is running) ----
    FieldSpec(DataType.Type.ELEVATION_GRADE, "GRADE", FieldKind.GRADE, HudIcon.DISTANCE, unit = "%", valueField = DataType.Field.ELEVATION_GRADE),
    FieldSpec(DataType.Type.VERTICAL_SPEED, "VAM", FieldKind.NUMBER, HudIcon.DISTANCE, unit = "VAM", valueField = DataType.Field.VERTICAL_SPEED),
    FieldSpec(DataType.Type.DISTANCE_TO_TOP, "TO TOP", FieldKind.DISTANCE, HudIcon.DISTANCE, unit = "to top", valueField = DataType.Field.DISTANCE_TO_TOP),
    FieldSpec(DataType.Type.ELEVATION_TO_TOP, "ELEV TOP", FieldKind.NUMBER, HudIcon.DISTANCE, unit = "m top", valueField = DataType.Field.ELEVATION_TO_TOP),
    FieldSpec(DataType.Type.CLIMB_NUMBER, "CLIMB", FieldKind.CLIMB_STEPS, HudIcon.DISTANCE, unit = "climb"),
)

/** One rendered field: the value, its unit/label, a zone color, and an optional icon. */
data class HudCell(
    val value: String,
    val units: String,
    val color: HudColor = HudColor.WHITE,
    val icon: HudIcon? = null,
) {
    companion object {
        fun blank(dataTypeId: String) = HudCell("--", "", HudColor.WHITE, FieldFormat.iconFor(dataTypeId))
    }
}

/**
 * What the glasses render now: one list of [HudCell] per page, the current page, and ride
 * state (so the screen shows "waiting for ride" when idle and the HUD when recording).
 */
data class HudSnapshot(
    val pages: List<List<HudCell>>,
    val paused: Boolean,
    val recording: Boolean,
    val pageIndex: Int,
    /**
     * Index of a page that should take over the display (a live Strava segment or climb auto-page).
     * The bridge snaps to it on the rising edge and suppresses auto-cycling while it's set; null
     * means normal paging. Workout pages don't pin — they ride along in the cycle as before.
     */
    val pinnedPage: Int? = null,
    /** Rows to lay out (2 or 3); drives the screen's vertical placement and active-cell count. */
    val rows: Int = MAX_ROWS,
    /** Time of day "HH:mm" for the top-left corner; blank hides it. */
    val clock: String = "",
    /** Maverick glasses battery percentage for the top-right corner; null hides it. */
    val battery: Int? = null,
    /** Whether to draw each cell's icon next to its unit/label. */
    val showIcons: Boolean = false,
    /** Whether battery-saver ("ECO") is engaged — drives the top-left ECO badge on the glasses. */
    val eco: Boolean = false,
    /**
     * Heading-up route trajectory to draw when the trajectory map page is shown (chiefly on
     * descents, to read curves ahead). Null when there's no route/position to project.
     */
    val trajectory: Trajectory? = null,
    /**
     * Index in [pages] of the trajectory map page. When [pageIndex] equals it (and [trajectory] is
     * present) the glasses draw the polyline map instead of data cells. Null = no trajectory page.
     */
    val trajectoryPageIndex: Int? = null,
) {
    companion object {
        val empty = HudSnapshot(emptyList(), paused = false, recording = false, pageIndex = 0, rows = MAX_ROWS)
    }
}

/**
 * A heading-up route preview for the glasses: the upcoming path in a metre frame ([points], rider
 * at (0,0) looking up) plus a small corner [overlay] (speed + grade) so the map isn't only a line.
 * The renderer scales [points] to pixels by the current zoom.
 */
data class Trajectory(
    val points: List<MetersPoint>,
    val overlay: List<HudCell>,
)

object FieldFormat {

    private val specByDataType = FIELD_SPECS.associateBy { it.id }

    /** The spec for a data type id, if the HUD knows how to render it. */
    fun specFor(dataTypeId: String): FieldSpec? = specByDataType[dataTypeId]

    /** Icon for a data type, if it has a glyph asset (used for blanks and when HUD icons are on). */
    fun iconFor(dataTypeId: String): HudIcon? = specByDataType[dataTypeId]?.icon

    /** Short header label for a data type id — used by the settings field picker only. */
    fun labelFor(dataTypeId: String): String {
        specByDataType[dataTypeId]?.let { return it.label }
        var s = dataTypeId
        if (s.contains("::")) s = s.substringAfterLast("::")
        s = s.removePrefix("TYPE_").removeSuffix("_ID").replace('_', ' ').trim()
        return if (s.isEmpty()) "FIELD" else s.uppercase().take(8)
    }

    /**
     * Format any data type into a [HudCell]. Known fields ([FIELD_SPECS]) get tailored units, a
     * training-zone color and an icon; unknown ones fall back to their raw single value. [ctx]
     * carries cross-field state (last power, under-gear timer) that cadence coloring needs.
     */
    fun format(
        dataTypeId: String,
        state: StreamState,
        imperial: Boolean,
        zones: ZoneConfig,
        ctx: FormatContext? = null,
        gear: GearLayout = GearLayout(),
        extLabels: Map<String, String> = emptyMap(),
    ): HudCell {
        val spec = specByDataType[dataTypeId]
        return if (spec != null) formatSpec(spec, state, imperial, zones, ctx, gear) else formatGeneric(dataTypeId, state, extLabels)
    }

    /** HUD unit text for a spec — the explicit override, else the kind's base unit + any suffix. */
    private fun hudUnit(spec: FieldSpec, imperial: Boolean): String {
        spec.unit?.let { return it }
        val base = when (spec.kind) {
            FieldKind.POWER -> "W"
            FieldKind.HR -> "bpm"
            FieldKind.CADENCE -> "rpm"
            FieldKind.SPEED -> if (imperial) "mph" else "km/h"
            FieldKind.DISTANCE -> if (imperial) "mi" else "km"
            FieldKind.BALANCE -> "L/R %"
            else -> ""
        }
        return if (spec.suffix.isEmpty()) base else "$base ${spec.suffix}".trim()
    }

    /**
     * Whether a structured workout is loaded, from the [DataType.Type.WORKOUT_INTERVAL_COUNT]
     * stream — [DataType.Field.WORKOUT_STEP_COUNT] is only populated when a workout file is
     * present; the stream is Idle/NotAvailable otherwise.
     */
    fun workoutActive(intervalCount: StreamState): Boolean {
        val dp = (intervalCount as? StreamState.Streaming)?.dataPoint ?: return false
        return (dp.values[DataType.Field.WORKOUT_STEP_COUNT]?.toInt() ?: 0) > 0
    }

    /**
     * Whether a Strava live segment is currently running, from the
     * [DataType.Type.SEGMENT_DISTANCE_REMAINING] stream — the segment fields only stream a positive
     * distance-remaining while on a segment (Idle/zero otherwise). Gates and pins the segment page.
     */
    fun segmentActive(state: StreamState): Boolean {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return false
        val remaining = dp.values[DataType.Field.SEGMENT_DISTANCE_REMAINING] ?: dp.singleValue
        return (remaining ?: 0.0) > 0.0
    }

    /**
     * Whether the rider is on a climb, from the [DataType.Type.CLIMB] stream — distance/elevation to
     * the top only stream positive while on a climb. Gates and pins the climb page (which only
     * appears when no segment is running).
     */
    fun climbActive(state: StreamState): Boolean {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return false
        val toTop = dp.values[DataType.Field.DISTANCE_TO_TOP] ?: 0.0
        val elevToTop = dp.values[DataType.Field.ELEVATION_TO_TOP] ?: 0.0
        return toTop > 0.0 || elevToTop > 0.0
    }

    /** Live grade (%) from the [DataType.Type.ELEVATION_GRADE] stream; null when not streaming. */
    fun gradeOf(state: StreamState): Double? {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return null
        return dp.values[DataType.Field.ELEVATION_GRADE] ?: dp.singleValue
    }

    private fun targetOf(state: StreamState): WorkoutTarget {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return WorkoutTarget(null, null, null)
        return WorkoutTarget(
            dp.values[DataType.Field.WORKOUT_TARGET_VALUE],
            dp.values[DataType.Field.WORKOUT_TARGET_MIN_VALUE],
            dp.values[DataType.Field.WORKOUT_TARGET_MAX_VALUE],
        )
    }

    /**
     * Range-vs-target coloring: uses the target's min/max band when present, otherwise ±5% of
     * the target value — below → cyan, in band → green, above → red.
     */
    private fun rangeColor(current: Double?, t: WorkoutTarget): HudColor {
        if (current == null) return HudColor.WHITE
        val min = t.min
        val max = t.max
        return when {
            min != null && max != null -> when {
                current < min -> HudColor.CYAN
                current > max -> HudColor.RED
                else -> HudColor.GREEN
            }
            t.value != null -> {
                val tol = t.value * 0.05
                when {
                    current < t.value - tol -> HudColor.CYAN
                    current > t.value + tol -> HudColor.RED
                    else -> HudColor.GREEN
                }
            }
            else -> HudColor.WHITE
        }
    }

    /**
     * Synthetic cells for the next-climb radar page, rendered from the computed [NextClimb] rather
     * than a Karoo stream (the extension injects this map into the cell lookup and keeps these ids
     * out of subscription). Distances honour the imperial setting; grade is coloured by severity as
     * a "how much will this hurt" preview. Returns dashes when no climb is in range so a pinned page
     * still draws.
     */
    fun radarCells(climb: NextClimb?, imperial: Boolean): Map<String, HudCell> = mapOf(
        RouteRadar.FIELD_DISTANCE to HudCell(
            formatDistance(climb?.distanceToStart, imperial),
            if (imperial) "mi" else "km",
            HudColor.CYAN,
            HudIcon.DISTANCE,
        ),
        RouteRadar.FIELD_ETA to HudCell(
            // formatDuration expects milliseconds (see its note); ETA is seconds.
            formatDuration(climb?.etaSeconds?.let { it * 1000.0 }),
            "eta",
            HudColor.WHITE,
            HudIcon.TIME,
        ),
        RouteRadar.FIELD_GRADE to HudCell(
            climb?.grade?.let { "%.0f".format(it) } ?: "--",
            "%",
            gradeColor(climb?.grade),
            null,
        ),
        RouteRadar.FIELD_LENGTH to HudCell(
            formatDistance(climb?.length, imperial),
            "long",
            HudColor.WHITE,
            null,
        ),
    )

    /** Climb-gradient severity colour for the radar grade preview (green easy → red brutal). */
    private fun gradeColor(grade: Double?): HudColor = when {
        grade == null -> HudColor.WHITE
        grade < 4.0 -> HudColor.GREEN
        grade < 7.0 -> HudColor.YELLOW
        grade < 10.0 -> HudColor.ORANGE
        else -> HudColor.RED
    }

    /**
     * Fallback for fields the HUD has no [FieldSpec] for — chiefly extension-provided fields the
     * rider picked (MPA, time to summit, …). We can't know their units, so we render the raw single
     * value and label it with the extension's display name (shortened) when one is known.
     */
    private fun formatGeneric(dataTypeId: String, state: StreamState, extLabels: Map<String, String>): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        val v = raw?.singleValue
        val value = when {
            v == null -> "--"
            v == v.roundToInt().toDouble() -> v.roundToInt().toString()
            else -> "%.1f".format(v)
        }
        val unit = extLabels[dataTypeId]?.take(12).orEmpty()
        return HudCell(value, unit, HudColor.GREEN)
    }

    /** Render a known [FieldSpec] into a [HudCell], dispatching on [FieldSpec.kind]. */
    private fun formatSpec(spec: FieldSpec, state: StreamState, imperial: Boolean, zones: ZoneConfig, ctx: FormatContext?, gear: GearLayout): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        // Value via the spec's named field, else the point's single value.
        val v = raw?.let { dp -> spec.valueField?.let { dp.values[it] } ?: dp.singleValue }
        val unit = hudUnit(spec, imperial)
        // Stash workout targets for the live POWER/CADENCE range coloring below. The streams go
        // Idle outside a workout, which stashes an unset target and restores zone coloring.
        when (spec.id) {
            DataType.Type.WORKOUT_POWER_TARGET -> ctx?.powerTarget = targetOf(state)
            DataType.Type.WORKOUT_CADENCE_TARGET -> ctx?.cadenceTarget = targetOf(state)
        }
        return when (spec.kind) {
            FieldKind.POWER -> {
                // Cache live power so the next CADENCE update can evaluate the under-gear rule.
                if (spec.id == DataType.Type.POWER) ctx?.lastPowerWatts = v
                // While a workout step prescribes a target, live power reads "value/target" and
                // tracks the target's range; zone coloring otherwise.
                val target = if (spec.id == DataType.Type.POWER) ctx?.powerTarget?.takeIf { it.isSet } else null
                val color = when {
                    target != null -> rangeColor(v, target)
                    spec.zone -> powerColor(v, zones.ftp, zones.powerZones)
                    else -> HudColor.WHITE
                }
                val value = target?.displayValue?.let { "${v.intOrDash()}/$it" } ?: v.intOrDash()
                HudCell(value, unit, color, spec.icon)
            }
            FieldKind.HR -> {
                val color = if (spec.zone) hrColor(v, zones.maxHr, zones.hrZones) else HudColor.WHITE
                HudCell(v.intOrDash(), unit, color, spec.icon)
            }
            FieldKind.CADENCE -> {
                // Only the live cadence field gets the deviation/under-gear coloring (needs ctx);
                // a streaming workout cadence target takes precedence, as for power.
                val target = if (spec.id == DataType.Type.CADENCE) ctx?.cadenceTarget?.takeIf { it.isSet } else null
                val color = when {
                    target != null -> rangeColor(v, target)
                    spec.id == DataType.Type.CADENCE -> cadenceColor(v, zones.idealCadence, zones.ftp, ctx)
                    else -> HudColor.WHITE
                }
                val value = target?.displayValue?.let { "${v.intOrDash()}/$it" } ?: v.intOrDash()
                HudCell(value, unit, color, spec.icon)
            }
            FieldKind.SPEED -> HudCell(formatSpeed(v, imperial), unit, HudColor.WHITE, spec.icon)
            FieldKind.DISTANCE -> HudCell(formatDistance(v, imperial), unit, HudColor.WHITE, spec.icon)
            FieldKind.TIME -> HudCell(formatDuration(v), unit, HudColor.WHITE, spec.icon) // v is ms
            FieldKind.INTERVAL_TIME -> HudCell(formatDuration(v), unit, HudColor.WHITE, spec.icon) // v is ms
            // Strava ±delta vs PR/KOM (v is signed ms): ahead → green, behind → red.
            FieldKind.DELTA_TIME -> HudCell(formatDelta(v), unit, deltaColor(v), spec.icon)
            FieldKind.GRADE -> HudCell(v?.let { "%.1f".format(it) } ?: "--", unit, HudColor.WHITE, spec.icon)
            FieldKind.CLIMB_STEPS -> {
                val total = raw?.values?.get(DataType.Field.TOTAL_CLIMBS)?.toInt() ?: 0
                val current = (raw?.values?.get(DataType.Field.CLIMB_NUMBER) ?: 0.0).toInt()
                val text = when {
                    total > 0 -> "$current/$total"
                    current > 0 -> "$current"
                    else -> "--"
                }
                HudCell(text, unit, HudColor.WHITE, spec.icon)
            }
            FieldKind.RATIO -> HudCell(v?.let { "%.2f".format(it) } ?: "--", unit, HudColor.WHITE, spec.icon)
            FieldKind.NUMBER -> HudCell(v.intOrDash(), unit, HudColor.WHITE, spec.icon)
            FieldKind.STEPS -> {
                // Workout progress as "current/total"; the step count only streams mid-workout.
                val total = raw?.values?.get(DataType.Field.WORKOUT_STEP_COUNT)?.toInt() ?: 0
                val current = (raw?.values?.get(DataType.Field.WORKOUT_CURRENT_STEP) ?: 0.0).toInt()
                HudCell(if (total > 0) "$current/$total" else "--", unit, HudColor.WHITE, spec.icon)
            }
            FieldKind.BALANCE -> HudCell(formatBalance(raw), unit, balanceColor(raw), spec.icon)
            FieldKind.GEARS -> {
                val (gv, gu) = formatGears(raw, gear)
                HudCell(gv, gu, HudColor.WHITE, spec.icon)
            }
        }
    }

    // Power color: map %FTP into the rider's editable [DEFAULT_POWER_ZONES] bands (the 7-color
    // cyan→purple ramp). Boundaries come from settings; the color-per-index is fixed.
    private fun powerColor(watts: Double?, ftp: Int, zones: List<ZoneBand>): HudColor {
        if (watts == null || ftp <= 0) return HudColor.WHITE
        val pct = (watts / ftp) * 100.0
        val idx = zoneIndexByPct(zones, pct)
        return POWER_ZONE_COLORS.getOrElse(idx) { HudColor.WHITE }
    }

    // HR color: map %MaxHR into the editable [DEFAULT_HR_ZONES] (5-color Coggan, no Z0).
    private fun hrColor(bpm: Double?, maxHr: Int, zones: List<ZoneBand>): HudColor {
        if (bpm == null || maxHr <= 0) return HudColor.WHITE
        val pct = (bpm / maxHr) * 100.0
        val idx = zoneIndexByPct(zones, pct)
        return HR_ZONE_COLORS.getOrElse(idx) { HudColor.WHITE }
    }

    /**
     * Cadence bands as a *deviation* from the rider's configured ideal cadence (settings):
     *   delta = rpm - ideal. The band shape is asymmetric (low-cadence ranges are wider than
     *   the symmetric high-cadence ones), reflecting that grinding feels different at, say,
     *   -10 rpm than spinning at +10:
     *
     *     delta < -34       white  (extreme grinding — overridden to RED if under-gear holds)
     *     -34 .. -20        orange
     *     -19 .. -7         yellow
     *      -6 ..  +6        green  (on target)
     *      +7 .. +16        yellow
     *     +17 .. +26        orange
     *     delta > +26       red    (extreme spinning)
     *
     * Under-gear override: when delta < -34 (well below ideal) AND power is in Z3 or above
     * (≥0.75 FTP), sustained for ≥3 s, flip to RED. The 3-second hold uses
     * [FormatContext.underGearedSinceMs]; we clear it the moment the conjunction breaks so
     * brief dips don't latch. If [ideal] is 0/unset the bands fall back to centred on 94 rpm.
     */
    private fun cadenceColor(rpm: Double?, ideal: Int, ftp: Int, ctx: FormatContext?): HudColor {
        if (rpm == null) return HudColor.WHITE
        val centre = if (ideal > 0) ideal else 94
        val delta = rpm - centre

        val power = ctx?.lastPowerWatts
        val z3Watts = if (ftp > 0) ftp * 0.75 else null
        val underGeared = delta < -34 && power != null && z3Watts != null && power >= z3Watts
        if (ctx != null) {
            if (underGeared) {
                val now = ctx.nowMs()
                val since = ctx.underGearedSinceMs ?: now.also { ctx.underGearedSinceMs = it }
                if (now - since >= UNDER_GEAR_DWELL_MS) return HudColor.RED
            } else {
                ctx.underGearedSinceMs = null
            }
        }

        return when {
            delta > 26 -> HudColor.RED
            delta >= 17 -> HudColor.ORANGE
            delta >= 7 -> HudColor.YELLOW
            delta >= -6 -> HudColor.GREEN
            delta >= -19 -> HudColor.YELLOW
            delta >= -34 -> HudColor.ORANGE
            else -> HudColor.WHITE
        }
    }

    private const val UNDER_GEAR_DWELL_MS = 3_000L

    private fun Double?.intOrDash(): String = this?.roundToInt()?.toString() ?: "--"

    private fun formatSpeed(mps: Double?, imperial: Boolean): String {
        if (mps == null) return "--"
        val kmh = mps * 3.6
        val out = if (imperial) kmh * 0.621371 else kmh
        return "%.1f".format(out)
    }

    private fun formatDistance(meters: Double?, imperial: Boolean): String {
        if (meters == null) return "--"
        val km = meters / 1000.0
        val out = if (imperial) km * 0.621371 else km
        return if (out >= 100) "%.0f".format(out) else "%.1f".format(out)
    }

    /**
     * The Karoo SDK emits time fields ([Field.ELAPSED_TIME] et al.) as **milliseconds** —
     * the LONG_POSITIVE_OR_ZERO alternate type. Everything that calls this is expected to
     * pass ms (no callers should pre-divide).
     */
    private fun formatDuration(millis: Double?): String {
        if ((millis == null) || (millis < 0)) return "--:--"
        val s = (millis / 1000.0).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /**
     * Signed time delta (Strava vs PR/KOM), [millis] negative = ahead. Rendered as "±m:ss"
     * (or "±h:mm:ss" for the rare long delta). Null when the segment isn't reporting a delta.
     */
    private fun formatDelta(millis: Double?): String {
        if (millis == null) return "--"
        val totalSec = (abs(millis) / 1000.0).roundToInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val sign = if (millis < 0) "-" else "+"
        return if (h > 0) "$sign%d:%02d:%02d".format(h, m, s) else "$sign%d:%02d".format(m, s)
    }

    /** Ahead of PR/KOM (negative delta) → green, behind (positive) → red, even/unknown → white. */
    private fun deltaColor(millis: Double?): HudColor = when {
        millis == null -> HudColor.WHITE
        millis < 0 -> HudColor.GREEN
        millis > 0 -> HudColor.RED
        else -> HudColor.WHITE
    }

    private fun formatBalance(point: DataPoint?): String {
        val left = point?.values?.get(DataType.Field.PEDAL_POWER_BALANCE_LEFT)?.roundToInt() ?: return "--"
        return "$left/${100 - left}"
    }

    /**
     * 6-step ramp on the absolute deviation from 50/50, with tighter steps near neutral and
     * wider ones at the extremes — the band widths grow as imbalance grows, so color change
     * feels gradual near the center rather than snapping at fixed boundaries:
     *
     *     |Δ| ≤ 1   white   (effectively neutral)
     *     |Δ| 2-3   green   (acceptable spread)
     *     |Δ| 4-5   yellow
     *     |Δ| 6-8   orange
     *     |Δ| 9-12  red
     *     |Δ| ≥ 13  purple  (severe)
     *
     * Continuous RGB interpolation would need the projector to accept arbitrary rgba (not
     * verified) — sticking to the EvsKit palette keeps rendering predictable.
     */
    private fun balanceColor(point: DataPoint?): HudColor {
        val left = point?.values?.get(DataType.Field.PEDAL_POWER_BALANCE_LEFT) ?: return HudColor.WHITE
        return when (abs(left - 50.0)) {
            in 0.0..1.5 -> HudColor.WHITE
            in 1.5..3.5 -> HudColor.GREEN
            in 3.5..5.5 -> HudColor.YELLOW
            in 5.5..8.5 -> HudColor.ORANGE
            in 8.5..12.5 -> HudColor.RED
            else -> HudColor.PURPLE
        }
    }

    /**
     * Render the [DataType.Type.SHIFTING_GEARS] field as (value, unit). Teeth are resolved in
     * priority order:
     *   1. directly from the sensor ([Field.SHIFTING_FRONT_GEAR_TEETH] / `..._REAR_GEAR_TEETH`),
     *      which electronic groups (SRAM AXS, Di2) report;
     *   2. otherwise mapped from the reported gear *position* via the rider's configured drivetrain
     *      ([gear]) — but only if that drivetrain matches the bike (its ring/cog count equals the
     *      sensor's reported max). This is the "datasheet" lookup.
     * When teeth can't be resolved (no/ mismatched config), we fall back to the gear *position*
     * number "F/R" exactly as before. [gear].display picks teeth / ratio / inches.
     */
    private fun formatGears(point: DataPoint?, gear: GearLayout): Pair<String, String> {
        if (point == null) return "--/--" to ""
        val frontPos = point.values[DataType.Field.SHIFTING_FRONT_GEAR]?.roundToInt()
        val rearPos = point.values[DataType.Field.SHIFTING_REAR_GEAR]?.roundToInt()
        val frontMax = point.values[DataType.Field.SHIFTING_FRONT_GEAR_MAX]?.roundToInt()
        val rearMax = point.values[DataType.Field.SHIFTING_REAR_GEAR_MAX]?.roundToInt()

        // Gear position 1 is the leftmost cog: smallest chainring up front, largest cog at the rear.
        val frontTeeth = point.values[DataType.Field.SHIFTING_FRONT_GEAR_TEETH]?.roundToInt()
            ?: teethFromPosition(gear.front, frontPos, frontMax, largestFirst = false)
        val rearTeeth = point.values[DataType.Field.SHIFTING_REAR_GEAR_TEETH]?.roundToInt()
            ?: teethFromPosition(gear.rear, rearPos, rearMax, largestFirst = true)

        if (frontTeeth != null && rearTeeth != null && rearTeeth > 0) {
            return when (gear.display) {
                "ratio" -> "%.2f".format(frontTeeth.toDouble() / rearTeeth) to ""
                // Gear inches ≈ ratio × wheel diameter (700c ≈ 27").
                "inches" -> "${(frontTeeth.toDouble() / rearTeeth * 27.0).roundToInt()}" to "in"
                else -> "$frontTeeth/$rearTeeth" to "T"
            }
        }
        // Fallback: the gear position number, as before.
        return if (frontPos != null && rearPos != null) "$frontPos/$rearPos" to "" else "--/--" to ""
    }

    /**
     * Map a 1-based gear [pos] to a tooth count using the configured [teeth] list. Returns null
     * ("configured one isn't found") when there's no config, the position is out of range, or the
     * configured ring/cog count disagrees with the sensor's reported [max] — i.e. the saved
     * drivetrain doesn't match the bike. Position 1 is the leftmost cog: [largestFirst] = false for
     * chainrings (1 = smallest ring), true for the cassette (1 = largest cog).
     */
    private fun teethFromPosition(teeth: List<Int>, pos: Int?, max: Int?, largestFirst: Boolean): Int? {
        if (teeth.isEmpty() || pos == null || pos < 1) return null
        if (max != null && max != teeth.size) return null
        val ordered = if (largestFirst) teeth.sortedDescending() else teeth.sorted()
        return ordered.getOrNull(pos - 1)
    }
}
