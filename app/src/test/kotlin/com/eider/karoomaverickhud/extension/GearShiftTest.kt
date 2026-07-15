package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the gear-change ratio tag: teeth resolution, the ±10 % ratio colouring, and the build. */
class GearShiftTest {

    private val gear = GearLayout(
        front = listOf(48, 35),
        rear = listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33), // SRAM 10-33, 12s
        display = "teeth",
    )

    private fun point(vararg pairs: Pair<String, Double>): DataPoint =
        DataPoint(DataType.Type.SHIFTING_GEARS, mapOf(*pairs))

    @Test
    fun teethPreferSensorValues() {
        val p = point(
            DataType.Field.SHIFTING_FRONT_GEAR_TEETH to 48.0,
            DataType.Field.SHIFTING_REAR_GEAR_TEETH to 14.0,
        )
        assertEquals(48 to 14, GearShift.teeth(p, gear))
    }

    @Test
    fun teethNullWithoutConfigOrSensor() {
        val p = point(
            DataType.Field.SHIFTING_FRONT_GEAR to 1.0,
            DataType.Field.SHIFTING_REAR_GEAR to 5.0,
        )
        assertNull(GearShift.teeth(p, GearLayout()))
    }

    @Test
    fun bigDropIsCyan() {
        // Ratio dropped more than 10 % → much easier gear.
        assertEquals(HudColor.CYAN, GearShift.color(3.0, 2.6)) // -13 %
    }

    @Test
    fun slightDropIsGreen() {
        // Ratio dropped up to 10 % → slightly easier gear.
        assertEquals(HudColor.GREEN, GearShift.color(3.0, 2.85)) // -5 %
    }

    @Test
    fun slightRiseIsYellow() {
        // Ratio rose up to 10 % → slightly harder gear.
        assertEquals(HudColor.YELLOW, GearShift.color(3.0, 3.15)) // +5 %
    }

    @Test
    fun bigRiseIsOrange() {
        // Ratio rose more than 10 % → much harder gear.
        assertEquals(HudColor.ORANGE, GearShift.color(3.0, 3.45)) // +15 %
    }

    @Test
    fun boundariesFoldToTheMildBands() {
        // Exactly ±10 % is still a "slight" move (green easier / yellow harder).
        assertEquals(HudColor.GREEN, GearShift.color(100.0, 90.0)) // -10 %
        assertEquals(HudColor.YELLOW, GearShift.color(100.0, 110.0)) // +10 %
        // Just past ±10 % flips to the extreme bands.
        assertEquals(HudColor.CYAN, GearShift.color(100.0, 89.0)) // -11 %
        assertEquals(HudColor.ORANGE, GearShift.color(100.0, 111.0)) // +11 %
    }

    @Test
    fun suffixFormatsRatioAndColour() {
        // 48/14 = 3.43 → 48/13 = 3.69 is a +7.7 % jump (a harder cog) → yellow, shown as "3.69".
        val suffix = GearShift.suffix(48 to 14, 48 to 13)
        assertEquals("3.69", suffix?.ratio)
        assertEquals(HudColor.YELLOW, suffix?.color)
    }

    @Test
    fun suffixNullOnZeroRear() {
        assertNull(GearShift.suffix(48 to 0, 48 to 14))
    }
}
