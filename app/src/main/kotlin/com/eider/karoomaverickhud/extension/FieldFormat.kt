package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.roundToInt

/** Max cells the HUD shows per page: two edge columns (left+right) × three rows. */
const val MAX_CELLS = 6

/** Value color, mapped to an EvsColor by the screen. Drives training-zone coloring. */
enum class HudColor { GREEN, ORANGE, RED }

/** Small green glyph drawn next to a value, mapped to an asset by the screen. */
enum class HudIcon { POWER, SPEED, HEART, CADENCE }

/** Zone thresholds from the user's profile; 0 disables coloring for that field. */
data class ZoneConfig(val ftp: Int, val maxHr: Int, val idealCadence: Int) {
    companion object {
        val NONE = ZoneConfig(0, 0, 0)
    }
}

/**
 * The known fields with first-class formatting. [dataTypeId] stays aligned with
 * [DataType.Type] so the field picker, Karoo-page mirror, and icons agree.
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
}

/** One rendered field: an optional icon, the value, its unit, and a zone color. */
data class HudCell(
    val value: String,
    val units: String,
    val color: HudColor = HudColor.GREEN,
    val icon: HudIcon? = null,
) {
    companion object {
        fun blank(dataTypeId: String) = HudCell("--", "", HudColor.GREEN, FieldFormat.iconFor(dataTypeId))
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
) {
    companion object {
        val empty = HudSnapshot(emptyList(), paused = false, recording = false, pageIndex = 0)
    }
}

object FieldFormat {

    private val knownByDataType = HudFieldId.entries.associateBy { it.dataTypeId }

    /** Icon for a data type, if it has one (used for blanks and the field picker). */
    fun iconFor(dataTypeId: String): HudIcon? = when (dataTypeId) {
        DataType.Type.POWER -> HudIcon.POWER
        DataType.Type.CADENCE -> HudIcon.CADENCE
        DataType.Type.HEART_RATE -> HudIcon.HEART
        DataType.Type.SPEED, DataType.Type.AVERAGE_SPEED -> HudIcon.SPEED
        else -> null
    }

    /** Short header label for a data type id — used by the settings field picker only. */
    fun labelFor(dataTypeId: String): String {
        knownByDataType[dataTypeId]?.let { return it.label }
        var s = dataTypeId
        if (s.contains("::")) s = s.substringAfterLast("::")
        s = s.removePrefix("TYPE_").removeSuffix("_ID").replace('_', ' ').trim()
        return if (s.isEmpty()) "FIELD" else s.uppercase().take(8)
    }

    /**
     * Format any data type into a [HudCell]. Known fields get tailored units, an icon, and
     * a training-zone color; unknown ones fall back to their raw single value.
     */
    fun format(dataTypeId: String, state: StreamState, imperial: Boolean, zones: ZoneConfig): HudCell {
        val known = knownByDataType[dataTypeId]
        return if (known != null) formatKnown(known, state, imperial, zones) else formatGeneric(dataTypeId, state)
    }

    private fun formatGeneric(dataTypeId: String, state: StreamState): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        val v = raw?.singleValue
        val value = when {
            v == null -> "--"
            v == v.roundToInt().toDouble() -> v.roundToInt().toString()
            else -> "%.1f".format(v)
        }
        return HudCell(value, "", HudColor.GREEN, iconFor(dataTypeId))
    }

    private fun formatKnown(field: HudFieldId, state: StreamState, imperial: Boolean, zones: ZoneConfig): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        return when (field) {
            HudFieldId.POWER -> {
                val w = raw?.values?.get(DataType.Field.POWER)
                HudCell(w.intOrDash(), "W", powerColor(w, zones.ftp), HudIcon.POWER)
            }

            HudFieldId.CADENCE -> {
                val c = raw?.values?.get(DataType.Field.CADENCE)
                HudCell(c.intOrDash(), "rpm", cadenceColor(c, zones.idealCadence), HudIcon.CADENCE)
            }

            HudFieldId.HEART_RATE -> {
                val hr = raw?.values?.get(DataType.Field.HEART_RATE)
                HudCell(hr.intOrDash(), "bpm", hrColor(hr, zones.maxHr), HudIcon.HEART)
            }

            HudFieldId.SPEED -> {
                val mps = raw?.values?.get(DataType.Field.SPEED)
                HudCell(formatSpeed(mps, imperial), if (imperial) "mph" else "km/h", HudColor.GREEN, HudIcon.SPEED)
            }

            HudFieldId.AVG_SPEED -> {
                val mps = raw?.values?.get(DataType.Field.AVERAGE_SPEED)
                HudCell(formatSpeed(mps, imperial), if (imperial) "mph" else "km/h", HudColor.GREEN, HudIcon.SPEED)
            }

            HudFieldId.LR_BALANCE -> HudCell(formatBalance(raw), "%", HudColor.GREEN, null)

            HudFieldId.DISTANCE -> {
                val m = raw?.values?.get(DataType.Field.DISTANCE)
                HudCell(formatDistance(m, imperial), if (imperial) "mi" else "km", HudColor.GREEN, null)
            }

            HudFieldId.ELAPSED_TIME -> {
                val sec = raw?.values?.get(DataType.Field.ELAPSED_TIME)
                HudCell(formatDuration(sec), "", HudColor.GREEN, null)
            }
        }
    }

    // Training zones: red at/above threshold, green at endurance or below, orange between.
    private fun powerColor(watts: Double?, ftp: Int): HudColor {
        if (watts == null || ftp <= 0) return HudColor.GREEN
        val pct = watts / ftp
        return when {
            pct >= 0.95 -> HudColor.RED
            pct >= 0.75 -> HudColor.ORANGE
            else -> HudColor.GREEN
        }
    }

    private fun hrColor(bpm: Double?, maxHr: Int): HudColor {
        if (bpm == null || maxHr <= 0) return HudColor.GREEN
        val pct = bpm / maxHr
        return when {
            pct >= 0.90 -> HudColor.RED
            pct >= 0.75 -> HudColor.ORANGE
            else -> HudColor.GREEN
        }
    }

    // Cadence is about holding a target: green at/above ideal, red well below (mashing).
    private fun cadenceColor(rpm: Double?, ideal: Int): HudColor {
        if (rpm == null || ideal <= 0) return HudColor.GREEN
        return when {
            rpm >= ideal - 5 -> HudColor.GREEN
            rpm >= ideal - 20 -> HudColor.ORANGE
            else -> HudColor.RED
        }
    }

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

    private fun formatDuration(seconds: Double?): String {
        if ((seconds == null) || (seconds < 0)) return "--:--"
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun formatBalance(point: DataPoint?): String {
        val left = point?.values?.get(DataType.Field.PEDAL_POWER_BALANCE_LEFT)?.roundToInt() ?: return "--"
        return "$left/${100 - left}"
    }
}
