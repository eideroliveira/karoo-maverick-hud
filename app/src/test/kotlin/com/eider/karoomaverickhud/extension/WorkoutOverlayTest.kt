package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the mid-workout centre overlay ([FieldFormat.workoutOverlay]): the countdown mirrors the
 * INTERVAL field (ms in, 1 s lead, 0-clamp), the blink arms only for the last five displayed
 * seconds, and the avg/NP power cells run the real zone colouring.
 */
class WorkoutOverlayTest {

    private val zones = ZoneConfig(ftp = 200, maxHr = 185, idealCadence = 90)

    private fun remaining(ms: Double): StreamState = StreamState.Streaming(
        DataPoint(
            dataTypeId = DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
            values = mapOf(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to ms),
        ),
    )

    private fun watts(dataTypeId: String, value: Double): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = mapOf(dataTypeId to value)))

    private fun overlay(
        remain: StreamState = remaining(83_000.0),
        avg: StreamState = watts(DataType.Type.AVERAGE_POWER, 150.0),
        np: StreamState = watts(DataType.Type.NORMALIZED_POWER, 220.0),
    ): WorkoutOverlay = FieldFormat.workoutOverlay(remain, avg, np, zones)

    @Test
    fun countdownCarriesTheIntervalFieldsOneSecondLead() {
        // 83 s to step finish reads 1:22 — the same 1 s lead the INTERVAL data field applies.
        assertEquals("1:22", overlay(remaining(83_000.0)).remaining)
    }

    @Test
    fun countdownClampsAtZeroInsteadOfDashing() {
        assertEquals("0:00", overlay(remaining(500.0)).remaining)
    }

    @Test
    fun countdownDashesWhenTheStreamIsIdle() {
        val o = overlay(remain = StreamState.Idle)
        assertEquals("--:--", o.remaining)
        assertFalse(o.blink) // an untimed/rest step must not flash
    }

    @Test
    fun blinkArmsAtFiveDisplayedSecondsAndBelow() {
        assertFalse(overlay(remaining(7_000.0)).blink) // shows 0:06
        assertTrue(overlay(remaining(6_000.0)).blink)  // shows 0:05 — the threshold
        assertTrue(overlay(remaining(1_000.0)).blink)  // shows 0:00
    }

    @Test
    fun powerCellsAreZoneColoured() {
        val o = overlay()
        // 150 W at FTP 200 = 75% → Z3 (yellow); 220 W = 110% → Z5 (red).
        assertEquals("150", o.avg.value)
        assertEquals(HudColor.YELLOW, o.avg.color)
        assertEquals("220", o.np.value)
        assertEquals(HudColor.RED, o.np.color)
    }

    @Test
    fun powerCellsDashWithoutData() {
        val o = overlay(avg = StreamState.Idle, np = StreamState.NotAvailable)
        assertEquals("--", o.avg.value)
        assertEquals("--", o.np.value)
    }
}
