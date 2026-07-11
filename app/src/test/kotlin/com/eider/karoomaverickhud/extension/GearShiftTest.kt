package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the gear-change flash: teeth resolution, ±5 % ratio colouring, and the overlay build. */
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
    fun smallChangeIsGreen() {
        // 52/14 = 3.714 vs 48/13 = 3.692 → a 0.6 % move → the same gear → green.
        assertEquals(HudColor.GREEN, GearShift.color(3.714, 3.692))
    }

    @Test
    fun harderGearIsYellow() {
        // ratio grew more than 5 % → harder gear.
        assertEquals(HudColor.YELLOW, GearShift.color(3.0, 3.30)) // +10 %
    }

    @Test
    fun easierGearIsCyan() {
        // ratio dropped more than 5 % → easier gear.
        assertEquals(HudColor.CYAN, GearShift.color(3.0, 2.70)) // -10 %
    }

    @Test
    fun boundaryStaysGreen() {
        // Exactly ±5 % is still "the same gear".
        assertEquals(HudColor.GREEN, GearShift.color(100.0, 105.0))
        assertEquals(HudColor.GREEN, GearShift.color(100.0, 95.0))
    }

    @Test
    fun overlayFormatsRatioAndColour() {
        // 48/14 = 3.43 → 48/13 = 3.69 is a +7.7 % jump (a harder cog) → yellow, shown as "48/13".
        val overlay = GearShift.overlay(48 to 14, 48 to 13)
        assertEquals("48/13", overlay?.ratio)
        assertEquals(HudColor.YELLOW, overlay?.color)
    }

    @Test
    fun overlayNullOnZeroRear() {
        assertNull(GearShift.overlay(48 to 0, 48 to 14))
    }
}
