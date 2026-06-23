package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState

/**
 * Next-climb radar — a look-ahead auto-page that pins the HUD as the rider approaches a climb on a
 * loaded route, then hands off to the on-climb page once they hit the ramp. It answers "what's
 * coming and when": distance to the climb, ETA, average grade and length.
 *
 * The climb shapes come from the route's [OnNavigationState.NavigationState.NavigatingRoute.climbs]
 * (SDK 1.1.6+). Position along the route is derived from
 * [DataType.Type.DISTANCE_TO_DESTINATION] (`routeDistance − distanceToDestination`), and the ETA
 * from live [DataType.Type.SPEED]. The values are synthetic (not Karoo streams), so the extension
 * injects them into the cell map and keeps their ids out of stream subscription.
 *
 * Only [NavigationState.NavigatingRoute] is handled — navigating to a free POI destination carries
 * climbs but no total route distance to anchor progress against, so that case is left for later.
 */
object RouteRadar {
    /** Synthetic field ids for the radar page (rendered by [FieldFormat.radarCells], never streamed). */
    const val FIELD_DISTANCE = "maverick.radar.distance"
    const val FIELD_ETA = "maverick.radar.eta"
    const val FIELD_GRADE = "maverick.radar.grade"
    const val FIELD_LENGTH = "maverick.radar.length"

    private const val PREFIX = "maverick.radar."

    /** Whether an id is a synthetic radar field (so the pipeline injects it rather than subscribing). */
    fun isSynthetic(id: String): Boolean = id.startsWith(PREFIX)

    /** Pin when ~90 s from the climb at current speed, but never further out than 1 km. */
    const val LOOKAHEAD_SECONDS = 90.0
    const val LOOKAHEAD_CAP_METERS = 1_000.0

    /** Speed floor (m/s) so a near-stop doesn't blow the look-ahead window or ETA up to silly values. */
    private const val MIN_SPEED_MPS = 1.5

    /** Distance travelled along the route (m) = full route length − distance still to its end. */
    fun routeProgress(routeDistance: Double?, distanceToDestination: Double?): Double? {
        if (routeDistance == null || distanceToDestination == null) return null
        return routeDistance - distanceToDestination
    }

    /**
     * The next climb still ahead and within the look-ahead window, or null when no route/climbs are
     * loaded, none remain ahead, or the nearest one is still beyond the window.
     */
    fun nextClimb(
        climbs: List<OnNavigationState.NavigationState.Climb>,
        progressMeters: Double?,
        speedMps: Double?,
    ): NextClimb? {
        if (climbs.isEmpty() || progressMeters == null) return null
        val ahead = climbs.filter { it.startDistance > progressMeters }.minByOrNull { it.startDistance }
            ?: return null
        val distanceTo = ahead.startDistance - progressMeters
        val speed = (speedMps ?: 0.0).coerceAtLeast(MIN_SPEED_MPS)
        val window = (speed * LOOKAHEAD_SECONDS).coerceAtMost(LOOKAHEAD_CAP_METERS)
        if (distanceTo > window) return null
        return NextClimb(
            distanceToStart = distanceTo,
            etaSeconds = distanceTo / speed,
            grade = ahead.grade,
            length = ahead.length,
            totalElevation = ahead.totalElevation,
        )
    }

    /** Read [DataType.Field.DISTANCE_TO_DESTINATION] (m) from its stream, or null when not navigating. */
    fun distanceToDestination(state: StreamState): Double? {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return null
        return dp.values[DataType.Field.DISTANCE_TO_DESTINATION] ?: dp.singleValue
    }

    /** Read live speed (m/s) from the [DataType.Type.SPEED] stream. */
    fun speed(state: StreamState): Double? {
        val dp = (state as? StreamState.Streaming)?.dataPoint ?: return null
        return dp.values[DataType.Field.SPEED] ?: dp.singleValue
    }
}

/** Computed shape of the climb the rider is approaching; drives the radar page's synthetic cells. */
data class NextClimb(
    /** Distance from the rider to the climb's start (m). */
    val distanceToStart: Double,
    /** Predicted time to reach the climb's start at current speed (s). */
    val etaSeconds: Double,
    /** Average grade over the climb (%). */
    val grade: Double,
    /** Climb length (m). */
    val length: Double,
    /** Total ascent of the climb (m). */
    val totalElevation: Double,
)
