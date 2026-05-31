package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.roundToInt

/** Max cells the Maverick HUD shows per page (2×2 grid). */
const val MAX_CELLS = 4

/**
 * The ride values the HUD has first-class formatting for (label + units + value rule).
 * Anything else a Karoo page throws at us is still rendered, best-effort, by
 * [FieldFormat.format] — these just look nicer. Keep [dataTypeId] aligned with
 * [DataType.Type] so the field picker and the Karoo page mirror agree.
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

/** Snapshot of one field at one instant — what the glasses render. */
data class HudCell(val label: String, val value: String, val units: String) {
    companion object {
        fun blank(dataTypeId: String) = HudCell(FieldFormat.labelFor(dataTypeId), "--", "")
    }
}

/**
 * What the glasses render right now: one list of [HudCell] per page, plus the current
 * page and ride state. Pages come either from the user's custom layout or from
 * mirroring the Karoo's active page.
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

    /**
     * Format any data type into a [HudCell]. Known fields get tailored label/units;
     * unknown ones (other Karoo fields, extension fields) fall back to their raw
     * single value and a label derived from the id.
     */
    fun format(dataTypeId: String, state: StreamState, imperial: Boolean): HudCell {
        val known = knownByDataType[dataTypeId]
        return if (known != null) formatKnown(known, state, imperial) else formatGeneric(dataTypeId, state)
    }

    /** Short header label for a data type id, used by generic cells and the field picker. */
    fun labelFor(dataTypeId: String): String {
        knownByDataType[dataTypeId]?.let { return it.label }
        var s = dataTypeId
        if (s.contains("::")) s = s.substringAfterLast("::") // extension fields: "ext::typeId"
        s = s.removePrefix("TYPE_").removeSuffix("_ID").replace('_', ' ').trim()
        return if (s.isEmpty()) "FIELD" else s.uppercase().take(8)
    }

    private fun formatGeneric(dataTypeId: String, state: StreamState): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        val v = raw?.singleValue
        val value = when {
            v == null -> "--"
            v == v.roundToInt().toDouble() -> v.roundToInt().toString()
            else -> "%.1f".format(v)
        }
        return HudCell(labelFor(dataTypeId), value, "")
    }

    private fun formatKnown(field: HudFieldId, state: StreamState, imperial: Boolean): HudCell {
        val raw = (state as? StreamState.Streaming)?.dataPoint
        return when (field) {
            HudFieldId.POWER ->
                HudCell(field.label, raw.intOrDash(DataType.Field.POWER), "W")

            HudFieldId.CADENCE ->
                HudCell(field.label, raw.intOrDash(DataType.Field.CADENCE), "rpm")

            HudFieldId.LR_BALANCE ->
                HudCell(field.label, formatBalance(raw), "%")

            HudFieldId.SPEED -> {
                val mps = raw?.values?.get(DataType.Field.SPEED)
                HudCell(field.label, formatSpeed(mps, imperial), if (imperial) "mph" else "km/h")
            }

            HudFieldId.DISTANCE -> {
                val m = raw?.values?.get(DataType.Field.DISTANCE)
                HudCell(field.label, formatDistance(m, imperial), if (imperial) "mi" else "km")
            }

            HudFieldId.AVG_SPEED -> {
                val mps = raw?.values?.get(DataType.Field.AVERAGE_SPEED)
                HudCell(field.label, formatSpeed(mps, imperial), if (imperial) "mph" else "km/h")
            }

            HudFieldId.HEART_RATE ->
                HudCell(field.label, raw.intOrDash(DataType.Field.HEART_RATE), "bpm")

            HudFieldId.ELAPSED_TIME -> {
                val sec = raw?.values?.get(DataType.Field.ELAPSED_TIME)
                HudCell(field.label, formatDuration(sec), "")
            }
        }
    }

    private fun DataPoint?.intOrDash(fieldId: String): String {
        val v = this?.values?.get(fieldId) ?: return "--"
        return v.roundToInt().toString()
    }

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

    /**
     * L/R balance arrives as "% left" in field PEDAL_POWER_BALANCE_LEFT. We render it as
     * "L/R" (e.g. "52/48") for at-a-glance readability on a HUD.
     */
    private fun formatBalance(point: DataPoint?): String {
        val left = point?.values?.get(DataType.Field.PEDAL_POWER_BALANCE_LEFT)?.roundToInt()
            ?: return "--"
        val right = 100 - left
        return "$left/$right"
    }
}
