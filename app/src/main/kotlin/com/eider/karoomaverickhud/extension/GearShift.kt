package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint

/**
 * A short-lived tag appended to the live GEAR data field for [GearShift.VISIBLE_MS] after the rider
 * stops shifting: the new gear's ratio (chainring ÷ cassette, two decimals, e.g. "3.43") plus a
 * [color] that encodes how much the ratio changed versus the gear held before the shift — or, for a
 * quick multi-shift, before the whole burst of them ([GearShiftTracker]) — so the rider reads the
 * direction of the change (much/slightly harder or easier) at a glance. Built by
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
 * Tracks gear changes so a burst of quick shifts reads as a single change: while the tag is still up
 * from a previous shift, the next shift belongs to the same burst and keeps comparing against
 * [anchor] — the gear held *before* the burst started — instead of the gear it just left. So a rider
 * dumping three cogs at once sees the resulting ratio coloured against where they came from, rather
 * than against the intermediate gear they blew through in 200 ms. The [suffix] value keeps updating
 * with every shift in the burst; the caller restarts the visibility window on each one, so the tag
 * clears [GearShift.VISIBLE_MS] after the *last* shift.
 *
 * A shift that lands after the tag has cleared starts a fresh burst, anchored on the gear held until
 * then — the plain single-shift case.
 *
 * Pure and clock-injected ([advance] takes `nowMs`) so it's unit-testable; the live flow feeds it
 * `System.currentTimeMillis()` once per resolved gear change.
 */
data class GearShiftTracker(
    /** Gear the tag compares against: the one held before the current burst of shifts. */
    val anchor: Pair<Int, Int>? = null,
    /** Most recently resolved gear. */
    val gear: Pair<Int, Int>? = null,
    /** When the last shift landed; null before the first shift of the ride. */
    val lastShiftMs: Long? = null,
    /** The tag to show now, or null when there's nothing to show (first reading, or no ratio). */
    val suffix: GearShiftSuffix? = null,
) {
    fun advance(next: Pair<Int, Int>, nowMs: Long): GearShiftTracker {
        // First reading of the ride: adopt it silently, there's nothing to compare against yet.
        val prev = gear ?: return GearShiftTracker(gear = next)
        if (next == prev) return this
        // Still inside the visible window → same burst, so keep the pre-burst anchor.
        val burst = lastShiftMs != null && nowMs - lastShiftMs < GearShift.VISIBLE_MS
        val base = if (burst) anchor ?: prev else prev
        return GearShiftTracker(
            anchor = base,
            gear = next,
            lastShiftMs = nowMs,
            suffix = GearShift.suffix(base, next),
        )
    }
}

/**
 * Detects a gear change and produces the coloured ratio tag the GEAR field shows for a few seconds
 * afterwards. The teeth are resolved exactly as the GEAR field resolves them
 * ([FieldFormat.gearTeeth]) — sensor teeth first, else the configured drivetrain mapped from the
 * reported position — so a tag only appears when a genuine ratio is available. Quick multi-shifts are
 * collapsed into one change by [GearShiftTracker].
 *
 * Colour follows how the ratio (chainring ÷ cassette) moved relative to the pre-shift gear:
 *   - dropped more than [THRESHOLD_PCT] %  → cyan   (much easier)
 *   - dropped up to [THRESHOLD_PCT] %      → green  (slightly easier)
 *   - unchanged                            → white  (a burst that ended back where it started)
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
     * rise, orange when it rose past +[THRESHOLD_PCT] % (much harder). White when the ratio didn't
     * move at all — a burst of shifts that came back to the gear it started from.
     */
    fun color(prevRatio: Double, nextRatio: Double): HudColor {
        if (prevRatio <= 0.0) return HudColor.WHITE
        val deltaPct = (nextRatio - prevRatio) / prevRatio * 100.0
        return when {
            deltaPct < -THRESHOLD_PCT -> HudColor.CYAN
            deltaPct < 0.0 -> HudColor.GREEN
            deltaPct == 0.0 -> HudColor.WHITE
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
