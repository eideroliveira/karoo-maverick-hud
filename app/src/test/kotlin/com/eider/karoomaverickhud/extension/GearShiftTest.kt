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

    @Test
    fun firstReadingShowsNoTag() {
        val t = GearShiftTracker().advance(48 to 14, 1_000L)
        assertNull(t.suffix)
        assertEquals(48 to 14, t.gear)
    }

    @Test
    fun singleShiftComparesAgainstTheGearJustLeft() {
        val t = GearShiftTracker().advance(48 to 14, 0L).advance(48 to 13, 1_000L)
        assertEquals("3.69", t.suffix?.ratio) // 48/13
        assertEquals(HudColor.YELLOW, t.suffix?.color) // +7.7 % vs 48/14
    }

    @Test
    fun quickMultiShiftKeepsThePreBurstGearAsReference() {
        // Three cogs dumped in ~600 ms: 14 → 13 → 12 → 11. Each step is a small rise on its own, but
        // the burst as a whole is 48/14 → 48/11 (+27 %) → orange, showing the resulting 4.36.
        val t = GearShiftTracker()
            .advance(48 to 14, 0L)
            .advance(48 to 13, 1_000L)
            .advance(48 to 12, 1_200L)
            .advance(48 to 11, 1_600L)
        assertEquals(48 to 14, t.anchor)
        assertEquals("4.36", t.suffix?.ratio)
        assertEquals(HudColor.ORANGE, t.suffix?.color)
    }

    @Test
    fun shiftAfterTheWindowStartsAFreshComparison() {
        // The second shift lands once the tag has cleared, so it's judged on its own step only.
        val t = GearShiftTracker()
            .advance(48 to 14, 0L)
            .advance(48 to 13, 1_000L)
            .advance(48 to 12, 1_000L + GearShift.VISIBLE_MS)
        assertEquals(48 to 13, t.anchor)
        assertEquals("4.00", t.suffix?.ratio)
        assertEquals(HudColor.YELLOW, t.suffix?.color) // 48/13 → 48/12 is +8.3 %
    }

    @Test
    fun burstThatReturnsToItsStartIsNeutral() {
        // Shifted down then straight back up: the net move is nothing, so no easier/harder colour.
        val t = GearShiftTracker()
            .advance(48 to 14, 0L)
            .advance(48 to 17, 1_000L)
            .advance(48 to 14, 1_500L)
        assertEquals("3.43", t.suffix?.ratio)
        assertEquals(HudColor.WHITE, t.suffix?.color)
    }

    @Test
    fun repeatedSameGearIsNotAShift() {
        val shifted = GearShiftTracker().advance(48 to 14, 0L).advance(48 to 13, 1_000L)
        val again = shifted.advance(48 to 13, 1_200L)
        assertEquals(shifted, again) // unchanged: window not restarted, anchor kept
    }
}
