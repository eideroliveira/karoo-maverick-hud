package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint

/**
 * A brief centre overlay shown for [GearShift.VISIBLE_MS] right after the rider shifts: the new
 * chainring/cassette teeth (e.g. "48/14") plus a [color] that encodes how much the gear ratio
 * changed versus the gear held before the shift — so the rider reads the direction of the change
 * (harder / easier / about the same) without doing the arithmetic. Built by [GearShift.overlay].
 */
data class GearShiftOverlay(
    /** New chainring/cassette teeth, "front/rear" (e.g. "48/14"). */
    val ratio: String,
    val color: HudColor,
)

/**
 * Detects a gear change and renders the new ratio as a transient centre overlay. The teeth are
 * resolved exactly as the GEAR field resolves them ([FieldFormat.gearTeeth]) — sensor teeth first,
 * else the configured drivetrain mapped from the reported position — so the overlay only appears
 * when a genuine ratio is available.
 *
 * Colour follows how the ratio (chainring ÷ cassette) moved relative to the previous gear:
 *   - within ±[THRESHOLD_PCT] %  → green  (a trim/half-step shift, effectively the same gear)
 *   - more than +[THRESHOLD_PCT] % → yellow (bigger ratio → a harder gear)
 *   - more than −[THRESHOLD_PCT] % → cyan   (smaller ratio → an easier gear)
 */
object GearShift {
    /** How long the ratio stays on screen after a shift. */
    const val VISIBLE_MS = 3_000L

    /** ± band, in percent of the previous ratio, that still counts as "the same gear" (green). */
    const val THRESHOLD_PCT = 5.0

    /** Resolve (frontTeeth, rearTeeth) for a shifting point, or null when no teeth are available. */
    fun teeth(point: DataPoint?, gear: GearLayout): Pair<Int, Int>? =
        point?.let { FieldFormat.gearTeeth(it, gear) }

    /**
     * Colour for a shift from [prevRatio] to [nextRatio] (both = front ÷ rear). Green within the
     * ±[THRESHOLD_PCT] % band, yellow when the ratio grew past it, cyan when it dropped past it.
     */
    fun color(prevRatio: Double, nextRatio: Double): HudColor {
        if (prevRatio <= 0.0) return HudColor.GREEN
        val deltaPct = (nextRatio - prevRatio) / prevRatio * 100.0
        return when {
            deltaPct > THRESHOLD_PCT -> HudColor.YELLOW
            deltaPct < -THRESHOLD_PCT -> HudColor.CYAN
            else -> HudColor.GREEN
        }
    }

    /**
     * Build the overlay for a shift from gear [prev] to gear [next] (each = front/rear teeth), or
     * null when a ratio can't be formed (a zero/absent rear cog).
     */
    fun overlay(prev: Pair<Int, Int>, next: Pair<Int, Int>): GearShiftOverlay? {
        val prevRatio = ratioOf(prev) ?: return null
        val nextRatio = ratioOf(next) ?: return null
        return GearShiftOverlay("${next.first}/${next.second}", color(prevRatio, nextRatio))
    }

    private fun ratioOf(teeth: Pair<Int, Int>): Double? =
        if (teeth.second > 0) teeth.first.toDouble() / teeth.second else null
}
