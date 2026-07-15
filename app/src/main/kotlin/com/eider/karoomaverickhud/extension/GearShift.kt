package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint

/**
 * A short-lived tag appended to the live GEAR data field for [GearShift.VISIBLE_MS] right after the
 * rider shifts: the new gear's ratio (chainring ÷ cassette, two decimals, e.g. "3.43") plus a
 * [color] that encodes how much the ratio changed versus the gear held before the shift — so the
 * rider reads the direction of the change (much/slightly harder or easier) at a glance. Built by
 * [GearShift.suffix]; attached to the GEAR cell by the snapshot builder and drawn beside the
 * chainring/cassette teeth (see HudScreen.layoutCell). Absent (null) except in the brief post-shift
 * window, after which the GEAR field returns to its plain rendering.
 */
data class GearShiftSuffix(
    /** New gear ratio (front ÷ rear), formatted to two decimals (e.g. "3.43"). */
    val ratio: String,
    val color: HudColor,
)

/**
 * Detects a gear change and produces the coloured ratio tag the GEAR field shows for a few seconds
 * afterwards. The teeth are resolved exactly as the GEAR field resolves them
 * ([FieldFormat.gearTeeth]) — sensor teeth first, else the configured drivetrain mapped from the
 * reported position — so a tag only appears when a genuine ratio is available.
 *
 * Colour follows how the ratio (chainring ÷ cassette) moved relative to the previous gear:
 *   - dropped more than [THRESHOLD_PCT] %  → cyan   (much easier)
 *   - dropped up to [THRESHOLD_PCT] %      → green  (slightly easier)
 *   - rose up to [THRESHOLD_PCT] %         → yellow (slightly harder)
 *   - rose more than [THRESHOLD_PCT] %     → orange (much harder)
 */
object GearShift {
    /** How long the ratio tag stays appended to the GEAR field after a shift. */
    const val VISIBLE_MS = 5_000L

    /** Percent band (of the previous ratio) that separates a slight move from a big one. */
    const val THRESHOLD_PCT = 10.0

    /** Resolve (frontTeeth, rearTeeth) for a shifting point, or null when no teeth are available. */
    fun teeth(point: DataPoint?, gear: GearLayout): Pair<Int, Int>? =
        point?.let { FieldFormat.gearTeeth(it, gear) }

    /**
     * Colour for a shift from [prevRatio] to [nextRatio] (both = front ÷ rear). Cyan when the ratio
     * dropped past ∓[THRESHOLD_PCT] % (much easier), green for a smaller drop, yellow for a small
     * rise, orange when it rose past +[THRESHOLD_PCT] % (much harder).
     */
    fun color(prevRatio: Double, nextRatio: Double): HudColor {
        if (prevRatio <= 0.0) return HudColor.WHITE
        val deltaPct = (nextRatio - prevRatio) / prevRatio * 100.0
        return when {
            deltaPct < -THRESHOLD_PCT -> HudColor.CYAN
            deltaPct < 0.0 -> HudColor.GREEN
            deltaPct <= THRESHOLD_PCT -> HudColor.YELLOW
            else -> HudColor.ORANGE
        }
    }

    /**
     * Build the ratio tag for a shift from gear [prev] to gear [next] (each = front/rear teeth), or
     * null when a ratio can't be formed (a zero/absent rear cog).
     */
    fun suffix(prev: Pair<Int, Int>, next: Pair<Int, Int>): GearShiftSuffix? {
        val prevRatio = ratioOf(prev) ?: return null
        val nextRatio = ratioOf(next) ?: return null
        return GearShiftSuffix("%.2f".format(nextRatio), color(prevRatio, nextRatio))
    }

    private fun ratioOf(teeth: Pair<Int, Int>): Double? =
        if (teeth.second > 0) teeth.first.toDouble() / teeth.second else null
}
