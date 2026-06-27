package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the next-climb radar: look-ahead window/selection math and the synthetic cell rendering. */
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

    // ---- nextClimb: look-ahead window (~90 s, capped at 1 km) ----

    @Test
    fun pinsWithinTimeWindowNotBeyondIt() {
        // 10 m/s → window = min(900, 1000) = 900 m.
        assertNotNull(RouteRadar.nextClimb(listOf(climb(800.0)), progressMeters = 0.0, speedMps = 10.0))
        assertNull(RouteRadar.nextClimb(listOf(climb(950.0)), progressMeters = 0.0, speedMps = 10.0))
    }

    @Test
    fun distanceCapHoldsAtOneKilometreWhenFast() {
        // 20 m/s → time window would be 1800 m but the 1 km cap applies.
        assertNotNull(RouteRadar.nextClimb(listOf(climb(950.0)), progressMeters = 0.0, speedMps = 20.0))
        assertNull(RouteRadar.nextClimb(listOf(climb(1_100.0)), progressMeters = 0.0, speedMps = 20.0))
    }

    @Test
    fun speedFloorKeepsWindowSaneNearAStop() {
        // speed 0 is floored to 1.5 m/s → window = 135 m (and ETA stays finite).
        val nc = RouteRadar.nextClimb(listOf(climb(120.0)), progressMeters = 0.0, speedMps = 0.0)
        assertNotNull(nc)
        assertEquals(80.0, nc!!.etaSeconds, 0.001) // 120 / 1.5
        assertNull(RouteRadar.nextClimb(listOf(climb(200.0)), progressMeters = 0.0, speedMps = 0.0))
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

    // ---- synthetic id guard ----

    @Test
    fun syntheticIdsAreRecognisedAndRealOnesAreNot() {
        assertTrue(RouteRadar.isSynthetic(RouteRadar.FIELD_DISTANCE))
        assertTrue(RouteRadar.isSynthetic(RouteRadar.FIELD_ETA))
        assertFalse(RouteRadar.isSynthetic(DataType.Type.POWER))
    }

    // ---- radarCells rendering ----

    @Test
    fun rendersDistanceEtaGradeLength() {
        val nc = NextClimb(distanceToStart = 500.0, etaSeconds = 50.0, grade = 8.0, length = 1_200.0, totalElevation = 96.0)
        val cells = FieldFormat.radarCells(nc, imperial = false)
        // Sub-kilometre distance-to-climb renders in whole metres, not fractional km.
        assertEquals("500", cells[RouteRadar.FIELD_DISTANCE]!!.value)
        assertEquals("m", cells[RouteRadar.FIELD_DISTANCE]!!.units)
        assertEquals(HudColor.CYAN, cells[RouteRadar.FIELD_DISTANCE]!!.color)
        assertEquals("0:50", cells[RouteRadar.FIELD_ETA]!!.value)
        assertEquals("8", cells[RouteRadar.FIELD_GRADE]!!.value)
        assertEquals("1.2", cells[RouteRadar.FIELD_LENGTH]!!.value)
    }

    @Test
    fun gradeColourEscalatesWithSteepness() {
        fun colourAt(grade: Double) =
            FieldFormat.radarCells(NextClimb(100.0, 10.0, grade, 500.0, 40.0), imperial = false)[RouteRadar.FIELD_GRADE]!!.color
        assertEquals(HudColor.GREEN, colourAt(3.0))
        assertEquals(HudColor.YELLOW, colourAt(5.0))
        assertEquals(HudColor.ORANGE, colourAt(8.0))
        assertEquals(HudColor.RED, colourAt(12.0))
    }

    @Test
    fun cellsDashWhenNoClimbInRange() {
        val cells = FieldFormat.radarCells(null, imperial = false)
        assertEquals("--", cells[RouteRadar.FIELD_DISTANCE]!!.value)
        assertEquals("--:--", cells[RouteRadar.FIELD_ETA]!!.value)
        assertEquals("--", cells[RouteRadar.FIELD_GRADE]!!.value)
        assertEquals("--", cells[RouteRadar.FIELD_LENGTH]!!.value)
    }
}
