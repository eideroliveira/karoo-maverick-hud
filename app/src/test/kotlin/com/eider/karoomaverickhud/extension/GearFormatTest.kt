package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the gear field's teeth resolution: sensor teeth → position-mapped teeth → position. */
class GearFormatTest {

    private val gear = GearLayout(
        front = listOf(48, 35),
        rear = listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33), // SRAM 10-33, 12s
        display = "teeth",
    )

    private fun gears(vararg pairs: Pair<String, Double>): StreamState =
        StreamState.Streaming(DataPoint(DataType.Type.SHIFTING_GEARS, mapOf(*pairs)))

    private fun value(state: StreamState, g: GearLayout = gear): String =
        FieldFormat.format(DataType.Type.SHIFTING_GEARS, state, imperial = false, zones = ZoneConfig.NONE, ctx = null, gear = g).value

    @Test
    fun sensorTeethArePreferred() {
        val s = gears(
            DataType.Field.SHIFTING_FRONT_GEAR_TEETH to 50.0,
            DataType.Field.SHIFTING_REAR_GEAR_TEETH to 14.0,
        )
        assertEquals("50/14", value(s))
    }

    @Test
    fun positionMapsToTeethWhenConfigMatches() {
        // Position 1 = leftmost cog: front pos 2 = big ring (48); rear pos 4 = 4th-LARGEST cog.
        // Cassette largest→smallest: 33,28,24,21,... so rear pos 4 = 21T.
        val s = gears(
            DataType.Field.SHIFTING_FRONT_GEAR to 2.0,
            DataType.Field.SHIFTING_REAR_GEAR to 4.0,
            DataType.Field.SHIFTING_FRONT_GEAR_MAX to 2.0,
            DataType.Field.SHIFTING_REAR_GEAR_MAX to 12.0,
        )
        assertEquals("48/21", value(s))
    }

    @Test
    fun mismatchedConfigFallsBackToPosition() {
        // sensor says 11 cogs but the configured cassette has 12 → "configured one isn't found".
        val s = gears(
            DataType.Field.SHIFTING_FRONT_GEAR to 1.0,
            DataType.Field.SHIFTING_REAR_GEAR to 5.0,
            DataType.Field.SHIFTING_FRONT_GEAR_MAX to 2.0,
            DataType.Field.SHIFTING_REAR_GEAR_MAX to 11.0,
        )
        assertEquals("1/5", value(s))
    }

    @Test
    fun noConfigFallsBackToPosition() {
        val s = gears(
            DataType.Field.SHIFTING_FRONT_GEAR to 1.0,
            DataType.Field.SHIFTING_REAR_GEAR to 7.0,
        )
        assertEquals("1/7", value(s, GearLayout())) // empty layout
    }

    @Test
    fun ratioDisplay() {
        val s = gears(
            DataType.Field.SHIFTING_FRONT_GEAR_TEETH to 50.0,
            DataType.Field.SHIFTING_REAR_GEAR_TEETH to 14.0,
        )
        assertEquals("3.57", value(s, gear.copy(display = "ratio")))
    }
}
