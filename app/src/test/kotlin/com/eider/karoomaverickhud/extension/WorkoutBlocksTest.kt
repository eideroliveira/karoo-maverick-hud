package com.eider.karoomaverickhud.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the block·rep tracker: work/rest classification, rep counting within a block, block
 * breaks on a large rest (≥2× the in-block rests), multi-step reps, and the late-target race guard.
 */
class WorkoutBlocksTest {

    private val ftp = 250

    /** Advance one step held for [ticks] ticks (remaining counts down from its full duration). */
    private fun WorkoutBlocks.State.runStep(
        step: Int,
        watts: Double?,
        durSec: Double,
        ticks: Int = 3,
    ): WorkoutBlocks.State {
        var s = this
        for (i in 0 until ticks) {
            val remain = (durSec - i * durSec / ticks).coerceAtLeast(0.0)
            s = s.advance(WorkoutBlocks.Observation(step, watts, remain, ftp))
        }
        return s
    }

    // ---- classification ----

    @Test
    fun classifiesWorkVsRestByFtpFraction() {
        assertFalse(WorkoutBlocks.classifyWork(null, ftp)) // no target → rest
        assertFalse(WorkoutBlocks.classifyWork(0.0, ftp))
        assertFalse(WorkoutBlocks.classifyWork(125.0, ftp)) // 50% FTP → rest
        assertTrue(WorkoutBlocks.classifyWork(150.0, ftp)) // 60% FTP → work (threshold)
        assertTrue(WorkoutBlocks.classifyWork(300.0, ftp))
        assertTrue(WorkoutBlocks.classifyWork(100.0, 0)) // no FTP → any positive target is work
    }

    @Test
    fun isSyntheticMatchesOnlyOurIds() {
        assertTrue(WorkoutBlocks.isSynthetic(WorkoutBlocks.FIELD_REP))
        assertFalse(WorkoutBlocks.isSynthetic("TYPE_POWER_ID"))
    }

    // ---- counting ----

    @Test
    fun countsRepsThenBreaksBlockOnLargeRest() {
        var s = WorkoutBlocks.INITIAL
        s = s.runStep(1, 120.0, 600.0) // warm-up (48% FTP → rest)
        assertEquals("--", s.display)
        s = s.runStep(2, 280.0, 180.0) // first effort
        assertEquals("1·1", s.display)
        s = s.runStep(3, 100.0, 60.0) // short rest — display holds
        assertEquals("1·1", s.display)
        s = s.runStep(4, 280.0, 180.0)
        assertEquals("1·2", s.display)
        s = s.runStep(5, 100.0, 60.0)
        s = s.runStep(6, 280.0, 180.0)
        assertEquals("1·3", s.display)
        s = s.runStep(7, 100.0, 300.0) // big rest (5×) — display holds, ends the block
        assertEquals("1·3", s.display)
        s = s.runStep(8, 280.0, 180.0) // new block
        assertEquals("2·1", s.display)
        s = s.runStep(9, 100.0, 60.0)
        s = s.runStep(10, 280.0, 180.0)
        assertEquals("2·2", s.display)
    }

    @Test
    fun multiStepRepCountsOnce() {
        var s = WorkoutBlocks.INITIAL
        s = s.runStep(1, 120.0, 600.0) // warm-up
        s = s.runStep(2, 300.0, 120.0) // over
        assertEquals("1·1", s.display)
        s = s.runStep(3, 240.0, 120.0) // under — still work, same rep (no rest between)
        assertEquals("1·1", s.display)
        s = s.runStep(4, 100.0, 60.0) // rest
        assertEquals("1·1", s.display)
        s = s.runStep(5, 300.0, 120.0)
        assertEquals("1·2", s.display)
    }

    @Test
    fun lateTargetStillCountsRepExactlyOnce() {
        var s = WorkoutBlocks.INITIAL
        s = s.runStep(1, 120.0, 600.0) // warm-up
        s = s.runStep(2, 280.0, 180.0) // "1·1"
        s = s.runStep(3, 100.0, 60.0) // rest
        // Step 4 begins but the power-target stream still reports the rest's value for one tick.
        s = s.advance(WorkoutBlocks.Observation(4, 100.0, 180.0, ftp))
        assertEquals("1·1", s.display) // not yet recognised as work
        s = s.advance(WorkoutBlocks.Observation(4, 280.0, 178.0, ftp)) // target arrives
        assertEquals("1·2", s.display)
        s = s.advance(WorkoutBlocks.Observation(4, 280.0, 176.0, ftp)) // no double count
        assertEquals("1·2", s.display)
    }

    @Test
    fun noWorkoutResetsToDashes() {
        var s = WorkoutBlocks.INITIAL
        s = s.runStep(1, 120.0, 600.0)
        s = s.runStep(2, 280.0, 180.0)
        assertEquals("1·1", s.display)
        s = s.advance(WorkoutBlocks.Observation(null, null, 0.0, ftp)) // workout ended
        assertEquals("--", s.display)
    }
}
