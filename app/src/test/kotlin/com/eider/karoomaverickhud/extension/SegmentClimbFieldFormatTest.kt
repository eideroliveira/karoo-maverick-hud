package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the Strava-segment and climb fields, plus the segment/climb auto-page detection gates. */
class SegmentClimbFieldFormatTest {

    private val zones = ZoneConfig(ftp = 200, maxHr = 185, idealCadence = 90)

    private fun streaming(dataTypeId: String, vararg pairs: Pair<String, Double>): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId, mapOf(*pairs)))

    private fun cell(dataTypeId: String, state: StreamState, extLabels: Map<String, String> = emptyMap()): HudCell =
        FieldFormat.format(dataTypeId, state, imperial = false, zones = zones, extLabels = extLabels)

    // ---- Strava ±delta vs PR ----

    @Test
    fun prDeltaAheadIsNegativeAndGreen() {
        val c = cell(
            DataType.Type.SEGMENT_TIME_TO_PR,
            streaming(DataType.Type.SEGMENT_TIME_TO_PR, DataType.Field.SEGMENT_PR_DELTA_TIME to -5_000.0),
        )
        assertEquals("-0:05", c.value)
        assertEquals("vs PR", c.units)
        assertEquals(HudColor.GREEN, c.color)
    }

    @Test
    fun prDeltaBehindIsPositiveAndRed() {
        val c = cell(
            DataType.Type.SEGMENT_TIME_TO_PR,
            streaming(DataType.Type.SEGMENT_TIME_TO_PR, DataType.Field.SEGMENT_PR_DELTA_TIME to 72_000.0),
        )
        assertEquals("+1:12", c.value)
        assertEquals(HudColor.RED, c.color)
    }

    @Test
    fun prDeltaMissingDashes() {
        assertEquals("--", cell(DataType.Type.SEGMENT_TIME_TO_PR, StreamState.Idle).value)
    }

    // ---- climb fields ----

    @Test
    fun gradeShowsOneDecimalPercent() {
        val c = cell(
            DataType.Type.ELEVATION_GRADE,
            streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 7.2),
        )
        assertEquals("7.2", c.value)
        assertEquals("%", c.units)
    }

    // ---- climb-context grade: "live/avg-remaining" ----

    /** Format the CLIMB helper stream into [ctx], then the GRADE field, returning the GRADE cell. */
    private fun gradeOnClimb(climb: StreamState, gradeState: StreamState): HudCell {
        val ctx = FormatContext()
        FieldFormat.format(DataType.Type.CLIMB, climb, imperial = false, zones = zones, ctx = ctx)
        return FieldFormat.format(DataType.Type.ELEVATION_GRADE, gradeState, imperial = false, zones = zones, ctx = ctx)
    }

    @Test
    fun gradeShowsLiveOverAvgRemainingOnClimb() {
        // 210 m of elevation over 1800 m to the top → 11.7% average, rendered whole as "12".
        val c = gradeOnClimb(
            streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 1_800.0, DataType.Field.ELEVATION_TO_TOP to 210.0),
            streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 7.2),
        )
        assertEquals("7.2/12", c.value)
        assertEquals("%", c.units)
    }

    @Test
    fun gradeFallsBackToLiveWhenClimbStreamGoesIdle() {
        // Cresting / off-climb: the CLIMB stream is Idle, so the average clears and GRADE reads live alone.
        val c = gradeOnClimb(
            StreamState.Idle,
            streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 7.2),
        )
        assertEquals("7.2", c.value)
    }

    @Test
    fun gradeFallsBackToLiveAtTheCrestWhenNoDistanceRemains() {
        // Distance-to-top zero → no average (avoid div-by-zero), live grade alone.
        val c = gradeOnClimb(
            streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 0.0, DataType.Field.ELEVATION_TO_TOP to 0.0),
            streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 9.0),
        )
        assertEquals("9.0", c.value)
    }

    @Test
    fun gradeShowsLiveAloneWithoutClimbContext() {
        // No FormatContext (e.g. the descent-trajectory overlay path) → live grade alone.
        val c = cell(
            DataType.Type.ELEVATION_GRADE,
            streaming(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE to 7.2),
        )
        assertEquals("7.2", c.value)
    }

    @Test
    fun climbAvgRemainingGradeIsRiseOverRun() {
        assertEquals(
            11.666,
            FieldFormat.climbAvgRemainingGradeOf(
                streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 1_800.0, DataType.Field.ELEVATION_TO_TOP to 210.0),
            )!!,
            0.01,
        )
        assertNull(
            FieldFormat.climbAvgRemainingGradeOf(
                streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 0.0, DataType.Field.ELEVATION_TO_TOP to 50.0),
            ),
        )
        assertNull(FieldFormat.climbAvgRemainingGradeOf(StreamState.Idle))
    }

    @Test
    fun climbNumberShowsCurrentOverTotal() {
        val c = cell(
            DataType.Type.CLIMB_NUMBER,
            streaming(DataType.Type.CLIMB_NUMBER, DataType.Field.CLIMB_NUMBER to 2.0, DataType.Field.TOTAL_CLIMBS to 3.0),
        )
        assertEquals("3/3", c.value)
    }

    @Test
    fun climbNumberDashesOffClimb() {
        assertEquals("--", cell(DataType.Type.CLIMB_NUMBER, StreamState.Idle).value)
    }

    @Test
    fun elevationToTopRendersWholeMeters() {
        val c = cell(
            DataType.Type.ELEVATION_TO_TOP,
            streaming(DataType.Type.ELEVATION_TO_TOP, DataType.Field.ELEVATION_TO_TOP to 210.0),
        )
        assertEquals("210", c.value)
        assertEquals("m top", c.units)
    }

    // ---- extension fields (generic) ----

    @Test
    fun extensionFieldUsesDiscoveredLabelAsUnit() {
        val id = DataType.dataTypeId("powerext", "mpa")
        val c = cell(id, streaming(id, "value" to 320.0), extLabels = mapOf(id to "MPA"))
        assertEquals("320", c.value)
        assertEquals("MPA", c.units)
    }

    // ---- detection gates ----

    @Test
    fun segmentActiveGatesOnDistanceRemaining() {
        assertTrue(
            FieldFormat.segmentActive(
                streaming(DataType.Type.SEGMENT_DISTANCE_REMAINING, DataType.Field.SEGMENT_DISTANCE_REMAINING to 600.0),
            ),
        )
        assertFalse(
            FieldFormat.segmentActive(
                streaming(DataType.Type.SEGMENT_DISTANCE_REMAINING, DataType.Field.SEGMENT_DISTANCE_REMAINING to 0.0),
            ),
        )
        assertFalse(FieldFormat.segmentActive(StreamState.Idle))
    }

    @Test
    fun climbActiveGatesOnDistanceOrElevationToTop() {
        assertTrue(
            FieldFormat.climbActive(
                streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 1_800.0),
            ),
        )
        assertTrue(
            FieldFormat.climbActive(
                streaming(DataType.Type.CLIMB, DataType.Field.ELEVATION_TO_TOP to 120.0),
            ),
        )
        assertFalse(
            FieldFormat.climbActive(
                streaming(DataType.Type.CLIMB, DataType.Field.DISTANCE_TO_TOP to 0.0, DataType.Field.ELEVATION_TO_TOP to 0.0),
            ),
        )
        assertFalse(FieldFormat.climbActive(StreamState.Idle))
    }
}
