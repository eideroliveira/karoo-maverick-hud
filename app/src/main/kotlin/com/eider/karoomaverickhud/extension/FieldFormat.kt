package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.abs
import kotlin.math.roundToInt

/** The HUD draws two edge columns (first & last of a notional four), so the centre stays clear. */
const val COLUMNS = 2

/** Rows are user-selectable; the layout supports two or three. */
const val MIN_ROWS = 2
const val MAX_ROWS = 3

/** Absolute cell cap (two columns × three rows) — used to size the screen's element pool. */
const val MAX_CELLS = COLUMNS * MAX_ROWS

/** Cells shown for a given row count, clamped to the supported range. */
fun cellsForRows(rows: Int): Int = COLUMNS * rows.coerceIn(MIN_ROWS, MAX_ROWS)

/**
 * Value color, mapped to an EvsColor by the screen. Drives the 7-step training-zone palette:
 * Z0 sub-recovery (cyan), Z1 recovery (white), Z2 endurance (green), Z3 tempo (yellow),
 * Z4 threshold (orange), Z5 VO2max (red), Z6+ anaerobic/neuromuscular (purple). Non-training
 * fields and missing data stay [WHITE] (neutral — the color only "lights up" for effort).
 */
enum class HudColor { WHITE, GREEN, YELLOW, ORANGE, RED, PURPLE, CYAN }

/** Zone thresholds from the user's profile; 0 disables coloring for that field. */
data class ZoneConfig(val ftp: Int, val maxHr: Int, val idealCadence: Int) {
    companion object {
        val NONE = ZoneConfig(0, 0, 0)
    }
}

/**
 * Stream-local cross-field state that cadence colouring needs: the last power reading
 * (since under-gearing depends on `power >= Z3`) and the timestamp when the under-gear
 * condition first became true (since it has to hold for ≥3 s before turning red).
 *
 * Lives for the duration of one pipeline (instantiated in [com.eider.karoomaverickhud.extension.RideHudExtension]);
 * mutated only from the single scan coroutine, so no synchronisation needed.
 */
class FormatContext {
    var lastPowerWatts: Double? = null
    var underGearedSinceMs: Long? = null

    /** Wall-clock; abstracted so tests can drive time without `System.currentTimeMillis()`. */
    var nowMs: () -> Long = { System.currentTimeMillis() }
}

/**
 * The known fields with first-class formatting. [dataTypeId] stays aligned with
 * [DataType.Type] so the field picker and Karoo-page mirror agree.
 */
enum class HudFieldId(val dataTypeId: String, val label: String) {
    POWER(DataType.Type.POWER, "POWER"),
    CADENCE(DataType.Type.CADENCE, "CAD"),
    LR_BALANCE(DataType.Type.PEDAL_POWER_BALANCE, "L/R"),
    SPEED(DataType.Type.SPEED, "SPEED"),
    DISTANCE(DataType.Type.DISTANCE, "DIST"),
    AVG_SPEED(DataType.Type.AVERAGE_SPEED, "AVG"),
    HEART_RATE(DataType.Type.HEART_RATE, "HR"),
    ELAPSED_TIME(DataType.Type.ELAPSED_TIME, "TIME"),
    GEARS(DataType.Type.SHIFTING_GEARS, "GEAR"),
}

/** One rendered field: the value, its unit/label, and a zone color. */
data class HudCell(
    val value: String,
    val units: String,
    val color: HudColor = HudColor.WHITE,
) {
    companion object {
        fun blank(dataTypeId: String) = HudCell("--", "", HudColor.WHITE)
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
    /** Rows to lay out (2 or 3); drives the screen's vertical placement and active-cell count. */
    val rows: Int = MAX_ROWS,
    /** Time of day "HH:mm" for the top-left corner; blank hides it. */
    val clock: String = "",
    /** Maverick glasses battery percentage for the top-right corner; null hides it. */
    val battery: Int? = null,
) {
    companion object {
        val empty = HudSnapshot(emptyList(), paused = false, recording = false, pageIndex = 0, rows = MAX_ROWS)
    }
}

object FieldFormat {

    private val knownByDataType = HudFieldId.entries.associateBy { it.dataTypeId }

    /** Short header label for a data type id — used by the settings field picker only. */
    fun labelFor(dataTypeId: String): String {
        knownByDataType[dataTypeId]?.let { return it.label }
        var s = dataTypeId
        if (s.contains("::")) s = s.substringAfterLast("::")
        s = s.removePrefix("TYPE_").removeSuffix("_ID").replace('_', ' ').trim()
        return if (s.isEmpty()) "FIELD" else s.uppercase().take(8)
    }

    /**
     * Format any data type into a [HudCell]. Known fields get tailored units and a training-zone
     * color; unknown ones fall back to their raw single value. [ctx] carries cross-field state
     * (last power, under-gear timer) that cadence colouring needs; null disables those rules.
     */
    fun format(
        dataTypeId: String,
        state: StreamState,
        imperial: Boolean,
        zones: ZoneConfig,
        ctx: FormatContext? = null,
    ): HudCell {
        val known = knownByDataType[dataTypeId]
        val cell = if (known != null) formatKnown(known, state, imperial, zones, ctx) else formatGeneric(dataTypeId, state)
        // Average fields get an "avg" tag after the unit (e.g. "km/h avg", "W avg").
        return if (isAverage(dataTypeId)) cell.copy(units = if (cell.units.isBlank()) "avg" else "${cell.units} avg") else cell
    }

    private fun isAverage(dataTypeId: String): Boolean =
        dataTypeId.contains("AVERAGE", ignoreCase = true) || dataTypeId.contains("AVG", ignoreCase = true)

    /**
     * The auto workout page — rendered only when a workout is actually loaded to the ride
     * (gated on [Field.WORKOUT_STEP_COUNT] > 0, which is non-zero exactly when a structured
     * workout file is present). Layout (6 cells, slot order TL-TR-BL-BR-ML-MR):
     *
     *   TL: Power           TR: Cadence          (current values, coloured by range vs target)
     *   ML: Power tgt       MR: Cadence tgt      (the prescribed target value)
     *   BL: NP (interval)   BR: "step/total" + time-remaining-in-interval
     *
     * Range colouring uses the target's min/max band when present, otherwise ±5% of the
     * target value: below → cyan, in band → green, above → red.
     */
    fun workoutPage(
        powerTarget: StreamState,
        cadenceTarget: StreamState,
        power: StreamState,
        cadence: StreamState,
        intervalCount: StreamState,
        normalizedPower: StreamState,
        timeRemaining: StreamState,
    ): List<HudCell>? {
        // "Workout added to the ride" signal — step count is only populated when a structured
        // workout file is loaded; the stream is Idle/NotAvailable otherwise.
        val intervalDp = (intervalCount as? StreamState.Streaming)?.dataPoint ?: return null
        val stepCount = intervalDp.values[DataType.Field.WORKOUT_STEP_COUNT]?.toInt() ?: 0
        if (stepCount <= 0) return null
        val currentStep = (intervalDp.values[DataType.Field.WORKOUT_CURRENT_STEP] ?: 0.0).toInt()

        val curPower = (power as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.POWER)
        val pT = targetOf(powerTarget)
        val curCadence = (cadence as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.CADENCE)
        val cT = targetOf(cadenceTarget)
        val npWatts = (normalizedPower as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.NORMALIZED_POWER)
        // WORKOUT_TIME_TO_STEP_FINISH is the INT alt-type — convention is seconds (LONG ones
        // ship ms). Promote to ms so the shared formatter handles it.
        val secLeft = (timeRemaining as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH)
        val timeLeftStr = formatDuration(secLeft?.let { it * 1000 })

        val intervalLabel = "$currentStep/$stepCount"
        val intervalUnit = if (timeLeftStr == "--:--") "intvl" else "$timeLeftStr left"

        return listOf(
            // TL, TR
            HudCell(curPower.intOrDash(), "W", rangeColor(curPower, pT)),
            HudCell(curCadence.intOrDash(), "rpm", rangeColor(curCadence, cT)),
            // BL, BR
            HudCell(npWatts.intOrDash(), "W NP", HudColor.WHITE),
            HudCell(intervalLabel, intervalUnit, HudColor.WHITE),
            // ML, MR
            HudCell(pT.value?.roundToInt()?.toString() ?: "--", "W tgt", HudColor.WHITE),
            HudCell(cT.value?.roundToInt()?.toString() ?: "--", "rpm tgt", HudColor.WHITE),
        )
    }

    private data class Target(val value: Double?, val min: Double?, val max: Double?)

    private fun targetOf(state: StreamState): Target {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return Target(null, null, null)
        return Target(
            dp.values[DataType.Field.WORKOUT_TARGET_VALUE],
            dp.values[DataType.Field.WORKOUT_TARGET_MIN_VALUE],
            dp.values[DataType.Field.WORKOUT_TARGET_MAX_VALUE],
        )
    }

    private fun rangeColor(current: Double?, t: Target): HudColor {
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

    private fun formatGeneric(dataTypeId: String, state: StreamState): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        val v = raw?.singleValue
        val value = when {
            v == null -> "--"
            v == v.roundToInt().toDouble() -> v.roundToInt().toString()
            else -> "%.1f".format(v)
        }
        return HudCell(value, "", HudColor.GREEN)
    }

    private fun formatKnown(field: HudFieldId, state: StreamState, imperial: Boolean, zones: ZoneConfig, ctx: FormatContext?): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        return when (field) {
            HudFieldId.POWER -> {
                val w = raw?.values?.get(DataType.Field.POWER)
                // Cache the latest power reading so the next CADENCE update can evaluate
                // the under-gear rule (rpm <60 sustained while power ≥ Z3 → red).
                ctx?.lastPowerWatts = w
                HudCell(w.intOrDash(), "W", powerColor(w, zones.ftp))
            }

            HudFieldId.CADENCE -> {
                val c = raw?.values?.get(DataType.Field.CADENCE)
                HudCell(c.intOrDash(), "rpm", cadenceColor(c, zones.idealCadence, zones.ftp, ctx))
            }

            HudFieldId.HEART_RATE -> {
                val hr = raw?.values?.get(DataType.Field.HEART_RATE)
                HudCell(hr.intOrDash(), "bpm", hrColor(hr, zones.maxHr))
            }

            HudFieldId.SPEED -> {
                val mps = raw?.values?.get(DataType.Field.SPEED)
                HudCell(formatSpeed(mps, imperial), if (imperial) "mph" else "km/h", HudColor.WHITE)
            }

            HudFieldId.AVG_SPEED -> {
                val mps = raw?.values?.get(DataType.Field.AVERAGE_SPEED)
                HudCell(formatSpeed(mps, imperial), if (imperial) "mph" else "km/h", HudColor.WHITE)
            }

            HudFieldId.LR_BALANCE -> HudCell(formatBalance(raw), "L/R %", balanceColor(raw))

            HudFieldId.DISTANCE -> {
                val m = raw?.values?.get(DataType.Field.DISTANCE)
                HudCell(formatDistance(m, imperial), if (imperial) "mi" else "km", HudColor.WHITE)
            }

            HudFieldId.ELAPSED_TIME -> {
                val ms = raw?.values?.get(DataType.Field.ELAPSED_TIME)
                HudCell(formatDuration(ms), "", HudColor.WHITE)
            }

            HudFieldId.GEARS -> HudCell(formatGears(raw), "T", HudColor.WHITE)
        }
    }

    // Power zones by %FTP, 7-step palette:
    //   Z0 <45% cyan · Z1 45-55% white · Z2 55-75% green · Z3 75-90% yellow ·
    //   Z4 90-105% orange · Z5 105-120% red · Z6+ ≥120% purple.
    private fun powerColor(watts: Double?, ftp: Int): HudColor {
        if (watts == null || ftp <= 0) return HudColor.WHITE
        val pct = watts / ftp
        return when {
            pct >= 1.20 -> HudColor.PURPLE
            pct >= 1.05 -> HudColor.RED
            pct >= 0.90 -> HudColor.ORANGE
            pct >= 0.75 -> HudColor.YELLOW
            pct >= 0.55 -> HudColor.GREEN
            pct >= 0.45 -> HudColor.WHITE
            else -> HudColor.CYAN
        }
    }

    // HR zones by %MaxHR — same colour order as power, with HR-appropriate thresholds.
    private fun hrColor(bpm: Double?, maxHr: Int): HudColor {
        if (bpm == null || maxHr <= 0) return HudColor.WHITE
        val pct = bpm / maxHr
        return when {
            pct >= 1.00 -> HudColor.PURPLE
            pct >= 0.90 -> HudColor.RED
            pct >= 0.80 -> HudColor.ORANGE
            pct >= 0.70 -> HudColor.YELLOW
            pct >= 0.60 -> HudColor.GREEN
            pct >= 0.50 -> HudColor.WHITE
            else -> HudColor.CYAN
        }
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

    private fun formatBalance(point: DataPoint?): String {
        val left = point?.values?.get(DataType.Field.PEDAL_POWER_BALANCE_LEFT)?.roundToInt() ?: return "--"
        return "$left/${100 - left}"
    }

    /**
     * 6-step ramp on the absolute deviation from 50/50, with tighter steps near neutral and
     * wider ones at the extremes — the band widths grow as imbalance grows, so colour change
     * feels gradual near the centre rather than snapping at fixed boundaries:
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
     * Render the [DataType.Type.SHIFTING_GEARS] field as "front/rear" teeth (e.g. "50/14") —
     * the SDK exposes [Field.SHIFTING_FRONT_GEAR_TEETH] and [Field.SHIFTING_REAR_GEAR_TEETH]
     * sourced from the rider's saved gearing info. Falls back to position-style "F/R" (e.g.
     * "2/8") if teeth aren't configured, then "--/--" if nothing's reported.
     */
    private fun formatGears(point: DataPoint?): String {
        if (point == null) return "--/--"
        val frontTeeth = point.values[DataType.Field.SHIFTING_FRONT_GEAR_TEETH]?.roundToInt()
        val rearTeeth = point.values[DataType.Field.SHIFTING_REAR_GEAR_TEETH]?.roundToInt()
        if (frontTeeth != null && rearTeeth != null) return "$frontTeeth/$rearTeeth"
        val frontPos = point.values[DataType.Field.SHIFTING_FRONT_GEAR]?.roundToInt()
        val rearPos = point.values[DataType.Field.SHIFTING_REAR_GEAR]?.roundToInt()
        return if (frontPos != null && rearPos != null) "$frontPos/$rearPos" else "--/--"
    }
}
