/* ============================================================
   FieldCatalog.kt — presentation metadata for the settings UI:
   the pickable fields (only ones the HUD can render), their
   icons/units/zone kind, preview coloring, and drivetrain presets.
   Mirrors data.jsx FIELDS / FIELD_GROUPS / DRIVETRAINS, but keyed
   by real Karoo data-type ids so the picker only offers what works.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import androidx.compose.ui.graphics.Color
import com.eider.karoomaverickhud.extension.FIELD_SPECS
import com.eider.karoomaverickhud.extension.FieldKind
import com.eider.karoomaverickhud.extension.FieldSpec
import com.eider.karoomaverickhud.extension.HR_ZONE_COLORS
import com.eider.karoomaverickhud.extension.HudColor
import com.eider.karoomaverickhud.extension.POWER_ZONE_COLORS
import com.eider.karoomaverickhud.extension.ZoneBand
import com.eider.karoomaverickhud.extension.zoneIndexByPct
import io.hammerhead.karooext.models.DataType
import kotlin.math.abs

enum class ZoneKind { POWER, HR, CADENCE }

/** One pickable field, keyed by its Karoo data-type id. */
data class UiField(
    val id: String,
    val label: String,
    val unit: String,
    val icon: String,
    val zone: ZoneKind?,
)

/** Settings-side icon name (KIcon) for a field kind. */
private fun iconName(kind: FieldKind): String = when (kind) {
    FieldKind.POWER -> "power"
    FieldKind.HR -> "heart"
    FieldKind.CADENCE -> "cadence"
    FieldKind.SPEED -> "speed"
    FieldKind.DISTANCE -> "distance"
    FieldKind.TIME, FieldKind.INTERVAL_TIME -> "time"
    FieldKind.BALANCE -> "balance"
    FieldKind.GEARS -> "gear"
    FieldKind.RATIO, FieldKind.NUMBER -> "bolt"
}

private fun zoneKind(kind: FieldKind): ZoneKind? = when (kind) {
    FieldKind.POWER -> ZoneKind.POWER
    FieldKind.HR -> ZoneKind.HR
    FieldKind.CADENCE -> ZoneKind.CADENCE
    else -> null
}

/** Metric HUD unit for the on-screen preview (mirrors FieldFormat.hudUnit, metric). */
private fun uiUnit(spec: FieldSpec): String {
    spec.unit?.let { return it }
    val base = when (spec.kind) {
        FieldKind.POWER -> "W"; FieldKind.HR -> "bpm"; FieldKind.CADENCE -> "rpm"
        FieldKind.SPEED -> "km/h"; FieldKind.DISTANCE -> "km"
        else -> "" // BALANCE: the "L/R %" label is the unit; avoid a redundant "· %"
    }
    return if (spec.suffix.isEmpty()) base else "$base ${spec.suffix}"
}

/** The fields the HUD can render, derived from the renderer's [FIELD_SPECS] catalog. */
val UI_FIELDS: Map<String, UiField> = FIELD_SPECS.associate { spec ->
    spec.id to UiField(spec.id, spec.label, uiUnit(spec), iconName(spec.kind), zoneKind(spec.kind))
}

/** Picker grouping (name → field ids). */
val UI_FIELD_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Power" to listOf(DataType.Type.POWER, DataType.Type.AVERAGE_POWER, DataType.Type.MAX_POWER, DataType.Type.NORMALIZED_POWER),
    "Cadence" to listOf(DataType.Type.CADENCE, DataType.Type.AVERAGE_CADENCE),
    "Heart" to listOf(DataType.Type.HEART_RATE, DataType.Type.AVERAGE_HR, DataType.Type.MAX_HR),
    "Speed & distance" to listOf(DataType.Type.SPEED, DataType.Type.AVERAGE_SPEED, DataType.Type.MAX_SPEED, DataType.Type.DISTANCE),
    "Time" to listOf(DataType.Type.ELAPSED_TIME, DataType.Type.ELAPSED_TIME_LAP, DataType.Type.ELAPSED_TIME_LAST_LAP, DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION),
    "This lap" to listOf(DataType.Type.DISTANCE_LAP, DataType.Type.AVERAGE_SPEED_LAP, DataType.Type.NORMALIZED_POWER_LAP, DataType.Type.AVERAGE_LAP_HR),
    "Last lap" to listOf(DataType.Type.DISTANCE_LAP_LAST_LAP, DataType.Type.AVERAGE_SPEED_LAST_LAP, DataType.Type.AVERAGE_POWER_LAST_LAP, DataType.Type.NORMALIZED_POWER_LAST_LAP, DataType.Type.AVERAGE_HR_LAST_LAP, DataType.Type.AVERAGE_CADENCE_LAST_LAP),
    "Training" to listOf(DataType.Type.INTENSITY_FACTOR, DataType.Type.VARIABILITY_INDEX, DataType.Type.TRAINING_STRESS_SCORE),
    "Drivetrain" to listOf(DataType.Type.SHIFTING_GEARS, DataType.Type.PEDAL_POWER_BALANCE),
)

/** Icon colour used next to a field in the picker — tinted by its zone kind. */
fun pickerIconColor(field: UiField): Color = when (field.zone) {
    ZoneKind.POWER -> K.zOrange
    ZoneKind.HR -> K.zRed
    ZoneKind.CADENCE -> K.zGreen
    null -> K.text
}

/**
 * Preview coloring — the same rules the glasses use, computed from a numeric demo value.
 * Power/HR use the editable bands; cadence uses the deviation bands (sans the under-gear
 * sustain rule, which needs live power history the preview doesn't have).
 */
fun previewColorFor(
    field: UiField?,
    value: Double?,
    ftp: Int,
    maxHr: Int,
    idealCadence: Int,
    powerZones: List<ZoneBand>,
    hrZones: List<ZoneBand>,
): Color {
    if (field?.zone == null || value == null) return K.zWhite
    return when (field.zone) {
        ZoneKind.POWER -> {
            if (ftp <= 0) K.zWhite
            else POWER_ZONE_COLORS.getOrElse(zoneIndexByPct(powerZones, value / ftp * 100.0)) { HudColor.WHITE }.toComposeColor()
        }
        ZoneKind.HR -> {
            if (maxHr <= 0) K.zWhite
            else HR_ZONE_COLORS.getOrElse(zoneIndexByPct(hrZones, value / maxHr * 100.0)) { HudColor.WHITE }.toComposeColor()
        }
        ZoneKind.CADENCE -> {
            if (idealCadence <= 0) K.zWhite
            else {
                val d = value - idealCadence
                when {
                    d > 26 -> K.zRed
                    d >= 17 -> K.zOrange
                    d >= 7 -> K.zYellow
                    d >= -6 -> K.zGreen
                    d >= -19 -> K.zYellow
                    d >= -34 -> K.zOrange
                    else -> K.zWhite
                }
            }
        }
    }
}

/** Balance coloring for the preview, mirroring FieldFormat.balanceColor's bands. */
fun previewBalanceColor(leftPct: Double?): Color {
    if (leftPct == null) return K.zWhite
    return when (abs(leftPct - 50.0)) {
        in 0.0..1.5 -> K.zWhite
        in 1.5..3.5 -> K.zGreen
        in 3.5..5.5 -> K.zYellow
        in 5.5..8.5 -> K.zOrange
        in 8.5..12.5 -> K.zRed
        else -> K.zPurple
    }
}

/* ============================================================
   Drivetrain datasheet — chainring combos and exact cassette cog
   arrays for common groupsets, used to resolve the HUD gear field's
   teeth from a reported gear position. Cog arrays compiled from
   manufacturer specs (SRAM X-Range / XPLR / Eagle, Shimano road,
   see the session notes for source URLs), 2026-06.
   ============================================================ */
data class Drivetrain(
    val id: String,
    val brand: String,
    val name: String,
    val front: List<Int>,
    val rear: List<Int>,
    val speeds: Int,
    val electronic: Boolean,
)

/** Combined groupset presets — pick one to set both chainrings and cassette at once. */
val DRIVETRAINS: List<Drivetrain> = listOf(
    // SRAM AXS road (XDR, X-Range)
    Drivetrain("sram_red_1033", "SRAM", "RED AXS 48/35 · 10-33", listOf(48, 35), listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33), 12, true),
    Drivetrain("sram_red_1028", "SRAM", "RED AXS 50/37 · 10-28", listOf(50, 37), listOf(10, 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 28), 12, true),
    Drivetrain("sram_force_1033", "SRAM", "Force AXS 48/35 · 10-33", listOf(48, 35), listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33), 12, true),
    Drivetrain("sram_rival_1036", "SRAM", "Rival AXS 46/33 · 10-36", listOf(46, 33), listOf(10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 36), 12, true),
    Drivetrain("sram_red_xplr", "SRAM", "RED XPLR 1× 44 · 10-44", listOf(44), listOf(10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44), 12, true),
    Drivetrain("sram_mullet", "SRAM", "AXS Mullet 1× 40 · 10-52", listOf(40), listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 38, 44, 52), 12, true),
    // Shimano Di2 12-speed road
    Drivetrain("di2_dura_1130", "Shimano", "Dura-Ace Di2 54/40 · 11-30", listOf(54, 40), listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30), 12, true),
    Drivetrain("di2_ult_1134", "Shimano", "Ultegra Di2 52/36 · 11-34", listOf(52, 36), listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34), 12, true),
    Drivetrain("di2_105_1134", "Shimano", "105 Di2 50/34 · 11-34", listOf(50, 34), listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34), 12, true),
    Drivetrain("grx_di2_1134", "Shimano", "GRX Di2 48/31 · 11-34", listOf(48, 31), listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34), 12, true),
    // Shimano mechanical 11-speed road
    Drivetrain("ult_r8000_1130", "Shimano", "Ultegra R8000 52/36 · 11-30", listOf(52, 36), listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30), 11, false),
    Drivetrain("shi_105_1132", "Shimano", "105 R7000 50/34 · 11-32", listOf(50, 34), listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 32), 11, false),
    // Generic 1× gravel
    Drivetrain("custom_1x_gravel", "Custom", "1× Gravel 40 · 10-44", listOf(40), listOf(10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44), 12, false),
)

fun drivetrainById(id: String?): Drivetrain? = DRIVETRAINS.firstOrNull { it.id == id }

/** A named cassette with its exact cog tooth array (smallest → largest). */
data class Cassette(val id: String, val label: String, val cogs: List<Int>)

/** Cassette datasheet — pick one to set the rear cog array exactly (real, not evenly spaced). */
val CASSETTES: List<Cassette> = listOf(
    Cassette("sram_10_26_12", "SRAM 10-26 · 12s", listOf(10, 11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 26)),
    Cassette("sram_10_28_12", "SRAM 10-28 · 12s", listOf(10, 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 28)),
    Cassette("sram_10_30_12", "SRAM 10-30 · 12s", listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30)),
    Cassette("sram_10_33_12", "SRAM 10-33 · 12s", listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33)),
    Cassette("sram_10_36_12", "SRAM 10-36 · 12s", listOf(10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 36)),
    Cassette("sram_xplr_10_44_12", "SRAM XPLR 10-44 · 12s", listOf(10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44)),
    Cassette("sram_eagle_10_50_12", "SRAM Eagle 10-50 · 12s", listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 38, 44, 50)),
    Cassette("sram_eagle_10_52_12", "SRAM Eagle 10-52 · 12s", listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 38, 44, 52)),
    Cassette("shi_11_30_12", "Shimano 11-30 · 12s", listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30)),
    Cassette("shi_11_34_12", "Shimano 11-34 · 12s", listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34)),
    Cassette("shi_11_25_11", "Shimano 11-25 · 11s", listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 25)),
    Cassette("shi_11_28_11", "Shimano 11-28 · 11s", listOf(11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28)),
    Cassette("shi_11_30_11", "Shimano 11-30 · 11s", listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30)),
    Cassette("shi_11_32_11", "Shimano 11-32 · 11s", listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 32)),
    Cassette("shi_11_34_11", "Shimano 11-34 · 11s", listOf(11, 13, 15, 17, 19, 21, 23, 25, 27, 30, 34)),
    Cassette("shi_12_25_11", "Shimano 12-25 · 11s", listOf(12, 13, 14, 15, 16, 17, 18, 19, 21, 23, 25)),
)
