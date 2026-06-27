package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the next-climb radar: look-ahead window/selection math and the centre-overlay rendering. */
class RouteRadarTest {

    private fun climb(start: Double, length: Double = 1_000.0, grade: Double = 6.0, ascent: Double = 80.0) =
        OnNavigationState.NavigationState.Climb(startDistance = start, length = length, grade = grade, totalElevation = ascent)

    private fun streaming(dataTypeId: String, vararg pairs: Pair<String, Double>): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId, mapOf(*pairs)))

    // ---- routeProgress ----

    @Test
    fun routeProgressIsLengthMinusRemaining() {
        assertEquals(1_000.0, RouteRadar.routeProgress(10_000.0, 9_000.0)!!, 0.001)
        assertNull(RouteRadar.routeProgress(null, 9_000.0))
        assertNull(RouteRadar.routeProgress(10_000.0, null))
    }

    // ---- nextClimb: selection ----

    @Test
    fun picksNearestClimbStillAhead() {
        val climbs = listOf(climb(8_000.0), climb(2_300.0, length = 400.0, grade = 12.0), climb(1_500.0))
        // progress 2_000 → the 1_500 climb is behind us; nearest ahead is 2_300 (300 m away at 10 m/s).
        val nc = RouteRadar.nextClimb(climbs, progressMeters = 2_000.0, speedMps = 10.0)
        assertNotNull(nc)
        assertEquals(300.0, nc!!.distanceToStart, 0.001)
        assertEquals(30.0, nc.etaSeconds, 0.001)
        assertEquals(12.0, nc.grade, 0.001)
        assertEquals(400.0, nc.length, 0.001)
    }

    @Test
    fun nullWhenNoClimbAheadOrNoData() {
        assertNull(RouteRadar.nextClimb(listOf(climb(1_000.0)), progressMeters = 5_000.0, speedMps = 10.0))
        assertNull(RouteRadar.nextClimb(emptyList(), progressMeters = 0.0, speedMps = 10.0))
        assertNull(RouteRadar.nextClimb(listOf(climb(500.0)), progressMeters = null, speedMps = 10.0))
    }

    // ---- nextClimb: look-ahead window (~45 s, capped at 1 km) ----

    @Test
    fun pinsWithinTimeWindowNotBeyondIt() {
        // 10 m/s → window = min(450, 1000) = 450 m.
        assertNotNull(RouteRadar.nextClimb(listOf(climb(400.0)), progressMeters = 0.0, speedMps = 10.0))
        assertNull(RouteRadar.nextClimb(listOf(climb(500.0)), progressMeters = 0.0, speedMps = 10.0))
    }

    @Test
    fun distanceCapHoldsAtOneKilometreWhenFast() {
        // 30 m/s → time window would be 1350 m but the 1 km cap applies.
        assertNotNull(RouteRadar.nextClimb(listOf(climb(950.0)), progressMeters = 0.0, speedMps = 30.0))
        assertNull(RouteRadar.nextClimb(listOf(climb(1_100.0)), progressMeters = 0.0, speedMps = 30.0))
    }

    @Test
    fun speedFloorKeepsWindowSaneNearAStop() {
        // speed 0 is floored to 1.5 m/s → window = 67.5 m (and ETA stays finite).
        val nc = RouteRadar.nextClimb(listOf(climb(60.0)), progressMeters = 0.0, speedMps = 0.0)
        assertNotNull(nc)
        assertEquals(40.0, nc!!.etaSeconds, 0.001) // 60 / 1.5
        assertNull(RouteRadar.nextClimb(listOf(climb(120.0)), progressMeters = 0.0, speedMps = 0.0))
    }

    // ---- stream readers ----

    @Test
    fun readsDistanceToDestinationAndSpeed() {
        assertEquals(
            9_000.0,
            RouteRadar.distanceToDestination(
                streaming(DataType.Type.DISTANCE_TO_DESTINATION, DataType.Field.DISTANCE_TO_DESTINATION to 9_000.0),
            )!!,
            0.001,
        )
        assertEquals(
            10.0,
            RouteRadar.speed(streaming(DataType.Type.SPEED, DataType.Field.SPEED to 10.0))!!,
            0.001,
        )
        assertNull(RouteRadar.distanceToDestination(StreamState.Idle))
        assertNull(RouteRadar.speed(StreamState.Idle))
    }

    // ---- radarOverlay rendering ----

    @Test
    fun overlayRendersDistanceEtaGradeLength() {
        val nc = NextClimb(distanceToStart = 500.0, etaSeconds = 50.0, grade = 8.0, length = 1_200.0, totalElevation = 96.0)
        val o = FieldFormat.radarOverlay(nc, imperial = false)!!
        assertEquals("0.5 km", o.distance)
        assertEquals("0:50", o.eta)
        assertEquals("8%", o.grade)
        assertEquals("1.2 km", o.length)
        assertEquals(HudColor.ORANGE, o.gradeColor)
    }

    @Test
    fun overlayGradeColourEscalatesWithSteepness() {
        fun colourAt(grade: Double) =
            FieldFormat.radarOverlay(NextClimb(100.0, 10.0, grade, 500.0, 40.0), imperial = false)!!.gradeColor
        assertEquals(HudColor.GREEN, colourAt(3.0))
        assertEquals(HudColor.YELLOW, colourAt(5.0))
        assertEquals(HudColor.ORANGE, colourAt(8.0))
        assertEquals(HudColor.RED, colourAt(12.0))
    }

    @Test
    fun overlayIsNullWhenNoClimbInRange() {
        assertNull(FieldFormat.radarOverlay(null, imperial = false))
    }
}
