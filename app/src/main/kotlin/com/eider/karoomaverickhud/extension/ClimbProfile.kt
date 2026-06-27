package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.OnNavigationState
import kotlin.math.max
import kotlin.math.min

/** One decoded (distance-along-route, elevation) sample from the route's elevation polyline (m). */
data class ElevSample(val distanceM: Double, val elevationM: Double)

/**
 * One filled column of the climb-profile silhouette: its horizontal span as a fraction of the climb
 * ([startFrac]..[endFrac], 0 = ramp, 1 = summit), its height as a fraction of the climb's total
 * relief ([heightFrac], 0..1), and the colour of the column's local grade. The renderer maps these
 * to pixels in the overlay's bottom third.
 */
data class ProfileBar(
    val startFrac: Float,
    val endFrac: Float,
    val heightFrac: Float,
    val color: HudColor,
)

/**
 * The active climb's elevation silhouette — grade-coloured columns plus the rider's position along
 * it ([progressFrac], 0 = ramp, 1 = summit). Null whenever there isn't enough elevation data to draw.
 */
data class ClimbProfileData(
    val bars: List<ProfileBar>,
    val progressFrac: Float,
)

/**
 * Builds the filled, grade-coloured climb profile for the on-climb centre overlay (see
 * [FieldFormat.climbOverlay] / `HudScreen.renderClimb`). The [OnNavigationState.NavigationState.Climb]
 * model only carries the climb's average grade, so the silhouette is reconstructed from the route's
 * `routeElevationPolyline` (a distance/elevation polyline) sliced to the active climb's span. All pure
 * maths (unit-tested); the EvsKit `Rect` rendering lives in `HudScreen`.
 *
 * Distances are taken in the same frame the next-climb radar uses — `routeDistance −
 * distanceToDestination` against `Climb.startDistance` — so the slice lines up with the rider's
 * progress without re-deriving position from geometry.
 */
object ClimbProfile {
    /** Columns the silhouette is bucketed into across the climb's length. */
    const val BUCKETS = 24

    /** Shortest a column draws (fraction of full relief) so shallow stretches still show a sliver. */
    private const val MIN_BAR = 0.06f

    /** Match tolerance (m) when deciding which route climb the rider is currently on. */
    private const val MATCH_TOLERANCE_M = 30.0

    /** Decode the route's distance/elevation polyline (Google encoding, precision 1) into samples. */
    fun decodeElevation(encoded: String?): List<ElevSample> {
        if (encoded.isNullOrEmpty()) return emptyList()
        // The route polyline carries lat/lng; the elevation polyline reuses the same encoding with
        // (distance, elevation) in the (lat, lng) slots at precision 1.
        return RouteTrajectory.decode(encoded, precision = 1).map { ElevSample(it.lat, it.lng) }
    }

    /**
     * The route [climbs] entry the rider is currently on — the one whose
     * [start, start+length] span contains [progressMeters] (with a small tolerance) — or null when
     * none match (no route climbs, off any climb, or position unknown).
     */
    fun activeClimb(
        climbs: List<OnNavigationState.NavigationState.Climb>,
        progressMeters: Double?,
    ): OnNavigationState.NavigationState.Climb? {
        if (progressMeters == null) return null
        return climbs.firstOrNull {
            progressMeters >= it.startDistance - MATCH_TOLERANCE_M &&
                progressMeters <= it.startDistance + it.length + MATCH_TOLERANCE_M
        }
    }

    /**
     * Build the grade-coloured silhouette for [climb] from the route [elevation] samples, marking the
     * rider's position from [progressMeters]. Returns null when the slice has too little relief or
     * data to draw a meaningful profile (e.g. no route elevation, or a near-flat span).
     */
    fun build(
        elevation: List<ElevSample>,
        climb: OnNavigationState.NavigationState.Climb?,
        progressMeters: Double?,
        buckets: Int = BUCKETS,
    ): ClimbProfileData? {
        if (climb == null || elevation.size < 2 || buckets < 1) return null
        val start = climb.startDistance
        val end = climb.startDistance + climb.length
        if (end - start < 1.0) return null

        fun elevAt(d: Double): Double = interpolate(elevation, d)

        // Relief spans the whole slice (so rollers/dips set the true min/max, not just the endpoints).
        var minE = elevAt(start)
        var maxE = minE
        for (b in 0..buckets) {
            val e = elevAt(start + (end - start) * b / buckets)
            minE = min(minE, e)
            maxE = max(maxE, e)
        }
        val relief = maxE - minE
        if (relief < 1.0) return null

        val bars = ArrayList<ProfileBar>(buckets)
        for (b in 0 until buckets) {
            val dStart = start + (end - start) * b / buckets
            val dEnd = start + (end - start) * (b + 1) / buckets
            val eStart = elevAt(dStart)
            val eEnd = elevAt(dEnd)
            val heightFrac = (((max(eStart, eEnd) - minE) / relief).toFloat()).coerceIn(MIN_BAR, 1f)
            val seg = dEnd - dStart
            val grade = if (seg > 0.0) (eEnd - eStart) / seg * 100.0 else 0.0
            bars.add(
                ProfileBar(
                    startFrac = b.toFloat() / buckets,
                    endFrac = (b + 1).toFloat() / buckets,
                    heightFrac = heightFrac,
                    color = gradeColor(grade),
                ),
            )
        }
        val progressFrac = progressMeters
            ?.let { (((it - start) / (end - start)).toFloat()).coerceIn(0f, 1f) } ?: 0f
        return ClimbProfileData(bars, progressFrac)
    }

    /** Elevation at distance [d] by linear interpolation across the (ascending) [samples]. */
    private fun interpolate(samples: List<ElevSample>, d: Double): Double {
        val first = samples.first()
        val last = samples.last()
        if (d <= first.distanceM) return first.elevationM
        if (d >= last.distanceM) return last.elevationM
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val c = samples[i]
            if (d <= c.distanceM) {
                val span = c.distanceM - a.distanceM
                if (span <= 0.0) return c.elevationM
                val t = (d - a.distanceM) / span
                return a.elevationM + (c.elevationM - a.elevationM) * t
            }
        }
        return last.elevationM
    }

    /**
     * Climb-gradient severity colour (green easy → red brutal), shared by the next-climb radar
     * preview and the on-climb profile so both read the same way.
     */
    fun gradeColor(grade: Double): HudColor = when {
        grade < 4.0 -> HudColor.GREEN
        grade < 7.0 -> HudColor.YELLOW
        grade < 10.0 -> HudColor.ORANGE
        else -> HudColor.RED
    }
}
