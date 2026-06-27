package com.eider.karoomaverickhud.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the debounced + hysteretic show/hide gate for the descent trajectory overlay. */
class DescentGateTest {

    private fun run(gate: DescentGate, vararg ticks: Pair<Double?, Long>): DescentGate {
        var g = gate
        for ((grade, t) in ticks) g = g.advance(grade, t)
        return g
    }

    @Test
    fun showsOnlyAfterSustainedDescent() {
        // Descending the whole time, but not shown until HOLD_MS has elapsed.
        var g = DescentGate.INITIAL.advance(-3.0, 0)
        assertFalse("not yet — hold not elapsed", g.shown)
        g = g.advance(-3.0, 2_000)
        assertFalse(g.shown)
        g = g.advance(-3.0, 3_000)
        assertTrue("shows once -2% has held for HOLD_MS", g.shown)
    }

    @Test
    fun briefDipDoesNotShow() {
        // A 1 s dip below -2% then back up — never reaches the hold, stays hidden.
        val g = run(DescentGate.INITIAL, -3.0 to 0L, -3.0 to 1_000L, -0.5 to 1_500L)
        assertFalse(g.shown)
    }

    @Test
    fun hidesOnlyAfterSustainedEase() {
        val shown = DescentGate(shown = true)
        // Grade eased to -0.5% (above HIDE, below immediate) — holds shown until HOLD_MS.
        var g = shown.advance(-0.5, 0)
        assertTrue(g.shown)
        g = g.advance(-0.5, 2_000)
        assertTrue(g.shown)
        g = g.advance(-0.5, 3_000)
        assertFalse("hides once eased for HOLD_MS", g.shown)
    }

    @Test
    fun hysteresisBandHoldsState() {
        // In the -2%..-1% band the gate keeps whatever it was doing, indefinitely.
        assertTrue(DescentGate(shown = true).advance(-1.5, 10_000).shown)
        assertFalse(DescentGate(shown = false).advance(-1.5, 10_000).shown)
    }

    @Test
    fun flatOrPositiveGradeHidesImmediately() {
        // No hold: a flat/positive grade drops the map on the next tick.
        assertFalse(DescentGate(shown = true).advance(0.0, 0).shown)
        assertFalse(DescentGate(shown = true).advance(3.0, 50).shown)
    }

    @Test
    fun nullGradeHoldsState() {
        assertTrue(DescentGate(shown = true).advance(null, 0).shown)
        assertFalse(DescentGate(shown = false).advance(null, 0).shown)
    }

    @Test
    fun interruptedDescentRestartsTheHold() {
        // Descend, briefly re-enter the band (resets the pending timer), then descend again — the
        // hold must restart from the second descent rather than carry the earlier elapsed time.
        var g = DescentGate.INITIAL.advance(-3.0, 0)   // pending since 0
        g = g.advance(-1.5, 1_000)                     // band → pending cleared
        g = g.advance(-3.0, 2_000)                     // pending restarts at 2_000
        g = g.advance(-3.0, 4_000)                     // only 2 s elapsed → still hidden
        assertFalse(g.shown)
        g = g.advance(-3.0, 5_000)                     // 3 s since 2_000 → shows
        assertTrue(g.shown)
    }
}
