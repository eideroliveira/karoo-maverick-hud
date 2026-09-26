package com.eider.karoomaverickhud.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeToExhaustionTest {

    private val tp = 250

    /**
     * Ride [seconds] of 1 Hz readings at constant [watts], with MPA draining exactly as the model says
     * (dMPA/dt = −[k]·(P − TP), floored at P) from [mpa0]. Returns the final state and MPA.
     */
    private fun ride(
        start: TimeToExhaustion.State,
        startSec: Int,
        seconds: Int,
        watts: Double,
        mpa0: Double,
        k: Double = 0.03,
    ): Pair<TimeToExhaustion.State, Double> {
        var s = start
        var mpa = mpa0
        for (i in 0 until seconds) {
            s = s.advance((startSec + i) * 1000L, watts, mpa, tp)
            mpa = maxOf(watts, mpa - k * (watts - tp))
        }
        return s to mpa
    }

    @Test
    fun isSyntheticMatchesOnlyItsId() {
        assertTrue(TimeToExhaustion.isSynthetic(TimeToExhaustion.FIELD_TTE))
        assertFalse(TimeToExhaustion.isSynthetic(WorkoutBlocks.FIELD_REP))
        assertFalse(TimeToExhaustion.isSynthetic("TYPE_POWER_ID"))
    }

    @Test
    fun belowThresholdHasNoEstimateAndNoWindow() {
        val (s, _) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 200.0, mpa0 = 900.0)
        assertNull(s.tteSec)
        assertTrue(s.window.isEmpty())
        assertNull(s.k)
    }

    @Test
    fun coldStartWaitsForAFitBeforeEstimating() {
        // Under MIN_FIT_SEC of above-threshold data: no k yet → "--".
        val (s, _) = ride(TimeToExhaustion.INITIAL, 0, 5, watts = 350.0, mpa0 = 900.0)
        assertNull(s.k)
        assertNull(s.tteSec)
    }

    @Test
    fun learnsTheDrainRateAndProjectsTimeToExhaustion() {
        // 100 W over TP at k = 0.03 → MPA falls 3 W/s.
        val (s, mpa) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 350.0, mpa0 = 900.0)
        assertEquals(0.03, s.k!!, 1e-6)
        // The state holds the last *fed* MPA (one drain step before the returned `mpa`).
        val fedMpa = mpa + 3.0
        assertEquals((fedMpa - 350.0) / 3.0, s.tteSec!!, 1e-3)
    }

    @Test
    fun aSurgeShortensTheEstimateImmediately() {
        val (learned, mpa) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 350.0, mpa0 = 900.0)
        val before = learned.tteSec!!
        // One tick at 450 W: smoothing only moves part of the way, but the projection uses the
        // learned k with the new (higher) power at once — no waiting for a new MPA slope.
        val after = learned.advance(20_000L, 450.0, mpa, tp)
        assertTrue("surge should cut TTE (was $before, now ${after.tteSec})", after.tteSec!! < before - 20.0)
    }

    @Test
    fun learnedRateSurvivesARecoveryAndAppliesToTheNextEffort() {
        val (learned, mpa) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 350.0, mpa0 = 900.0)
        // Ease off below threshold, MPA recovering from where the effort left it (the smoothed power
        // takes a couple of seconds to settle under TP; rising MPA then teaches nothing).
        val (rested, _) = ride(learned, 20, 30, watts = 150.0, mpa0 = mpa)
        assertNull(rested.tteSec)
        assertTrue(rested.window.isEmpty())
        // The first easing tick is still above TP after smoothing, so one transitional sample nudges
        // k by well under 1% — kept, not reset.
        assertEquals(0.03, rested.k!!, 1e-3)
        // Back above threshold: the very first readings already have an estimate. (The smoothed power
        // is still climbing back, so use a clearly-above target to clear MIN_EXCESS_W on tick 2.)
        var s = rested.advance(50_000L, 400.0, 800.0, tp)
        s = s.advance(51_000L, 400.0, 796.0, tp)
        assertNotNull(s.tteSec)
    }

    @Test
    fun flatMpaNeverTeachesARate() {
        var s = TimeToExhaustion.INITIAL
        for (i in 0 until 20) s = s.advance(i * 1000L, 350.0, 900.0, tp)
        assertNull(s.k)
        assertNull(s.tteSec)
    }

    @Test
    fun mpaAtOrBelowPowerReadsAsExhausted() {
        val (learned, _) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 350.0, mpa0 = 900.0)
        val s = learned.advance(20_000L, 350.0, 340.0, tp)
        assertEquals(0.0, s.tteSec!!, 0.0)
        assertEquals("0:00", TimeToExhaustion.display(s.tteSec))
        assertEquals(HudColor.RED, TimeToExhaustion.color(s.tteSec))
    }

    @Test
    fun missingPowerOrMpaClearsTheWindowButKeepsTheRate() {
        val (learned, mpa) = ride(TimeToExhaustion.INITIAL, 0, 20, watts = 350.0, mpa0 = 900.0)
        val noPower = learned.advance(20_000L, null, mpa, tp)
        assertTrue(noPower.window.isEmpty())
        assertNull(noPower.tteSec)
        assertEquals(learned.k, noPower.k)
        val noMpa = learned.advance(20_000L, 350.0, null, tp)
        assertTrue(noMpa.window.isEmpty())
        assertNull(noMpa.tteSec)
        assertEquals(learned.k, noMpa.k)
    }

    @Test
    fun noThresholdMeansNoEstimate() {
        var s = TimeToExhaustion.INITIAL
        var mpa = 900.0
        for (i in 0 until 20) {
            s = s.advance(i * 1000L, 350.0, mpa, 0)
            mpa -= 3.0
        }
        assertNull(s.k)
        assertNull(s.tteSec)
    }

    @Test
    fun observeKIgnoresShortOrRisingWindows() {
        val short = listOf(
            TimeToExhaustion.Sample(0.0, 900.0, 350.0),
            TimeToExhaustion.Sample(1.0, 897.0, 350.0),
            TimeToExhaustion.Sample(2.0, 894.0, 350.0),
        )
        assertNull(TimeToExhaustion.observeK(short, tp))
        val rising = (0..8).map { TimeToExhaustion.Sample(it.toDouble(), 800.0 + it, 350.0) }
        assertNull(TimeToExhaustion.observeK(rising, tp))
    }

    @Test
    fun displayAndColourBands() {
        assertEquals("--", TimeToExhaustion.display(null))
        assertEquals("2:30", TimeToExhaustion.display(150.0))
        assertEquals("19:59", TimeToExhaustion.display(1199.9))
        assertEquals("20+", TimeToExhaustion.display(1200.0))
        assertEquals(HudColor.WHITE, TimeToExhaustion.color(null))
        assertEquals(HudColor.RED, TimeToExhaustion.color(60.0))
        assertEquals(HudColor.ORANGE, TimeToExhaustion.color(150.0))
        assertEquals(HudColor.YELLOW, TimeToExhaustion.color(300.0))
        assertEquals(HudColor.GREEN, TimeToExhaustion.color(301.0))
    }

    @Test
    fun cellCarriesTheTimeGlyphAndTag() {
        val c = TimeToExhaustion.cell(150.0)
        assertEquals("2:30", c.value)
        assertEquals(TimeToExhaustion.UNIT, c.units)
        assertEquals(HudIcon.TIME, c.icon)
        assertEquals(TimeToExhaustion.LABEL, c.iconLabel)
        assertEquals(HudColor.ORANGE, c.color)
    }
}
