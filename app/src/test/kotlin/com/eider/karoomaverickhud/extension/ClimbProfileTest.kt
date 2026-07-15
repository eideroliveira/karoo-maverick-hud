package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the on-climb overlay: the elevation-profile geometry ([ClimbProfile]) and the readout
 * formatter ([FieldFormat.climbOverlay]).
 */
class ClimbProfileTest {

    private fun climb(start: Double, length: Double = 1_000.0, grade: Double = 10.0, ascent: Double = 100.0) =
        OnNavigationState.NavigationState.Climb(startDistance = start, length = length, grade = grade, totalElevation = ascent)

    private fun streaming(dataTypeId: String, vararg pairs: Pair<String, Double>): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId, mapOf(*pairs)))

    /** A straight 12% climb: distance 1000→2000 m, elevation 100→220 m, sampled every 100 m. */
    private fun straightClimb(): List<ElevSample> =
        (0..10).map { ElevSample(1_000.0 + it * 100.0, 100.0 + it * 12.0) }

    // ---- gradeColor ramp ----

    @Test
    fun gradeColourEscalatesWithSteepness() {
        assertEquals(HudColor.GREEN, ClimbProfile.gradeColor(2.0))
        assertEquals(HudColor.YELLOW, ClimbProfile.gradeColor(5.0))
        assertEquals(HudColor.ORANGE, ClimbProfile.gradeColor(8.0))
        assertEquals(HudColor.RED, ClimbProfile.gradeColor(10.0))
        assertEquals(HudColor.RED, ClimbProfile.gradeColor(14.0))
    }

    // ---- activeClimb ----

    @Test
    fun activeClimbMatchesTheSpanContainingProgress() {
        val climbs = listOf(climb(1_000.0), climb(5_000.0, length = 500.0))
        assertEquals(1_000.0, ClimbProfile.activeClimb(climbs, 1_500.0)!!.startDistance, 0.001)
        assertEquals(5_000.0, ClimbProfile.activeClimb(climbs, 5_400.0)!!.startDistance, 0.001)
        // Just inside the start tolerance.
        assertNotNull(ClimbProfile.activeClimb(climbs, 980.0))
        // Between climbs / past the last one / unknown position → no match.
        assertNull(ClimbProfile.activeClimb(climbs, 3_000.0))
        assertNull(ClimbProfile.activeClimb(climbs, 9_000.0))
        assertNull(ClimbProfile.activeClimb(climbs, null))
    }

    // ---- build ----

    @Test
    fun buildProducesGradeColouredBarsAndProgress() {
        val data = ClimbProfile.build(straightClimb(), climb(1_000.0), progressMeters = 1_500.0)
        assertNotNull(data)
        assertEquals(ClimbProfile.BUCKETS, data!!.bars.size)
        // A constant 12% climb → every column is red.
        assertTrue(data.bars.all { it.color == HudColor.RED })
        // Halfway along the climb.
        assertEquals(0.5f, data.progressFrac, 0.001f)
        // Monotonic rise: the silhouette grows toward the summit and tops out at full relief.
        assertTrue(data.bars.first().heightFrac < data.bars.last().heightFrac)
        assertEquals(1f, data.bars.last().heightFrac, 0.001f)
        // Columns tile [0,1] without gaps.
        assertEquals(0f, data.bars.first().startFrac, 0.001f)
        assertEquals(1f, data.bars.last().endFrac, 0.001f)
    }

    @Test
    fun buildColoursPerSegmentNotByAverage() {
        // Gentle then brutal: 100 m at ~2% (green) then 100 m at ~16% (red).
        val elev = listOf(
            ElevSample(0.0, 0.0),
            ElevSample(100.0, 2.0),
            ElevSample(200.0, 18.0),
        )
        val data = ClimbProfile.build(elev, climb(0.0, length = 200.0), progressMeters = 0.0, buckets = 2)!!
        assertEquals(HudColor.GREEN, data.bars[0].color)
        assertEquals(HudColor.RED, data.bars[1].color)
    }

    @Test
    fun buildClampsProgressToTheClimb() {
        val before = ClimbProfile.build(straightClimb(), climb(1_000.0), progressMeters = 500.0)!!
        val after = ClimbProfile.build(straightClimb(), climb(1_000.0), progressMeters = 9_000.0)!!
        assertEquals(0f, before.progressFrac, 0.001f)
        assertEquals(1f, after.progressFrac, 0.001f)
    }

    @Test
    fun buildIsNullWithoutUsableData() {
        // No climb / too few samples / degenerate span.
        assertNull(ClimbProfile.build(straightClimb(), climb = null, progressMeters = 1_500.0))
        assertNull(ClimbProfile.build(listOf(ElevSample(0.0, 0.0)), climb(0.0), progressMeters = 0.0))
        assertNull(ClimbProfile.build(straightClimb(), climb(1_000.0, length = 0.0), progressMeters = 1_000.0))
        // Flat span → no relief to draw.
        val flat = (0..10).map { ElevSample(1_000.0 + it * 100.0, 100.0) }
        assertNull(ClimbProfile.build(flat, climb(1_000.0), progressMeters = 1_500.0))
    }

    // ---- decodeElevation (Google polyline, precision 1) ----

    @Test
    fun decodeElevationRoundTrips() {
        val samples = listOf(
            ElevSample(0.0, 120.0),
            ElevSample(150.0, 135.5),
            ElevSample(420.0, 168.2),
        )
        val encoded = encodePrecision1(samples)
        val decoded = ClimbProfile.decodeElevation(encoded)
        assertEquals(samples.size, decoded.size)
        for (i in samples.indices) {
            assertEquals(samples[i].distanceM, decoded[i].distanceM, 0.05) // precision 1 → 0.1 m resolution
            assertEquals(samples[i].elevationM, decoded[i].elevationM, 0.05)
        }
        assertTrue(ClimbProfile.decodeElevation(null).isEmpty())
        assertTrue(ClimbProfile.decodeElevation("").isEmpty())
    }

    // ---- FieldFormat.climbOverlay ----

    @Test
    fun overlayFormatsSummaryFromTheClimbStream() {
        val climbState = streaming(
            DataType.Type.CLIMB,
            DataType.Field.DISTANCE_TO_TOP to 1_400.0,
            DataType.Field.ELEVATION_TO_TOP to 120.0,
            DataType.Field.CLIMB_NUMBER to 2.0,
            DataType.Field.TOTAL_CLIMBS to 3.0,
        )
        val gradeState = streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 6.0)
        val o = FieldFormat.climbOverlay(climbState, gradeState, mpaWatts = 320.0, profile = null, imperial = false)!!
        assertEquals("120 m", o.toTop)          // vertical ascent remaining
        assertEquals("1.4 km", o.toEnd)         // horizontal distance remaining
        assertEquals("6.0", o.grade)
        assertEquals(HudColor.YELLOW, o.gradeColor)
        assertEquals("8.6", o.avgGrade)         // 120 / 1400 = 8.57%
        assertEquals(HudColor.ORANGE, o.avgGradeColor)
        assertEquals("320 W", o.mpa)
        assertEquals("CLIMB 3/3", o.climbLabel)
    }

    @Test
    fun overlayOmitsMpaWhenAbsentAndLabelsWithoutIndex() {
        val climbState = streaming(
            DataType.Type.CLIMB,
            DataType.Field.DISTANCE_TO_TOP to 800.0,
            DataType.Field.ELEVATION_TO_TOP to 40.0,
        )
        val gradeState = streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 5.0)
        val o = FieldFormat.climbOverlay(climbState, gradeState, mpaWatts = null, profile = null, imperial = false)!!
        assertNull(o.mpa)
        assertEquals("CLIMB", o.climbLabel)
    }

    @Test
    fun overlayIsNullWhenNotOnAClimb() {
        // Both remaining fields zero / Idle stream → not on a climb.
        val off = streaming(
            DataType.Type.CLIMB,
            DataType.Field.DISTANCE_TO_TOP to 0.0,
            DataType.Field.ELEVATION_TO_TOP to 0.0,
        )
        val gradeState = streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 0.0)
        assertNull(FieldFormat.climbOverlay(off, gradeState, mpaWatts = 200.0, profile = null, imperial = false))
        assertNull(FieldFormat.climbOverlay(StreamState.Idle, gradeState, mpaWatts = null, profile = null, imperial = false))
    }

    /** Minimal Google-polyline encoder at precision 1, mirroring [RouteTrajectory.decode]. */
    private fun encodePrecision1(samples: List<ElevSample>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLng = 0
        for (s in samples) {
            val lat = Math.round(s.distanceM * 10).toInt()
            val lng = Math.round(s.elevationM * 10).toInt()
            encodeSigned(lat - prevLat, sb)
            encodeSigned(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeSigned(value: Int, sb: StringBuilder) {
        var v = value shl 1
        if (value < 0) v = v.inv()
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
}
