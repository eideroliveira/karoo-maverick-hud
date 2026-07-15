package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the workout fields: targets, step progress, and the range-vs-target coloring. */
class WorkoutFieldFormatTest {

    private val zones = ZoneConfig(ftp = 200, maxHr = 185, idealCadence = 90)

    private fun streaming(dataTypeId: String, vararg pairs: Pair<String, Double>): StreamState =
        StreamState.Streaming(DataPoint(dataTypeId, mapOf(*pairs)))

    private fun cell(dataTypeId: String, state: StreamState, ctx: FormatContext? = null): HudCell =
        FieldFormat.format(dataTypeId, state, imperial = false, zones = zones, ctx = ctx)

    @Test
    fun powerTargetShowsTargetValue() {
        val s = streaming(DataType.Type.WORKOUT_POWER_TARGET, DataType.Field.WORKOUT_TARGET_VALUE to 250.0)
        val c = cell(DataType.Type.WORKOUT_POWER_TARGET, s)
        assertEquals("250", c.value)
        assertEquals("W tgt", c.units)
    }

    @Test
    fun stepProgressShowsCurrentOverTotal() {
        val s = streaming(
            DataType.Type.WORKOUT_INTERVAL_COUNT,
            DataType.Field.WORKOUT_CURRENT_STEP to 3.0,
            DataType.Field.WORKOUT_STEP_COUNT to 12.0,
        )
        assertEquals("4/12", cell(DataType.Type.WORKOUT_INTERVAL_COUNT, s).value)
    }

    @Test
    fun stepProgressDashesOutsideWorkout() {
        assertEquals("--", cell(DataType.Type.WORKOUT_INTERVAL_COUNT, StreamState.Idle).value)
    }

    @Test
    fun livePowerTracksStreamingTarget() {
        val ctx = FormatContext()
        // Stash a 240-260 W band via the target field, as the pipeline would.
        cell(
            DataType.Type.WORKOUT_POWER_TARGET,
            streaming(
                DataType.Type.WORKOUT_POWER_TARGET,
                DataType.Field.WORKOUT_TARGET_VALUE to 250.0,
                DataType.Field.WORKOUT_TARGET_MIN_VALUE to 240.0,
                DataType.Field.WORKOUT_TARGET_MAX_VALUE to 260.0,
            ),
            ctx,
        )
        fun power(watts: Double): HudCell =
            cell(DataType.Type.POWER, streaming(DataType.Type.POWER, DataType.Field.POWER to watts), ctx)
        // Mid-workout the live value reads "value/target" and colors against the band.
        assertEquals("200/250", power(200.0).value)
        assertEquals(HudColor.CYAN, power(200.0).color)
        assertEquals(HudColor.GREEN, power(250.0).color)
        assertEquals(HudColor.RED, power(300.0).color)
    }

    @Test
    fun liveCadenceRendersValueOverTarget() {
        val ctx = FormatContext()
        cell(
            DataType.Type.WORKOUT_CADENCE_TARGET,
            streaming(DataType.Type.WORKOUT_CADENCE_TARGET, DataType.Field.WORKOUT_TARGET_VALUE to 90.0),
            ctx,
        )
        val c = cell(DataType.Type.CADENCE, streaming(DataType.Type.CADENCE, DataType.Field.CADENCE to 88.0), ctx)
        assertEquals("88/90", c.value)
        assertEquals(HudColor.GREEN, c.color) // within ±5% of 90
    }

    @Test
    fun perMetricFallback_powerOnlyWorkoutLeavesCadenceRegular() {
        // A power-only workout step: a power target streams, the cadence target stays Idle.
        // Power must go composite while cadence falls back to its regular field + coloring.
        val ctx = FormatContext()
        cell(
            DataType.Type.WORKOUT_POWER_TARGET,
            streaming(DataType.Type.WORKOUT_POWER_TARGET, DataType.Field.WORKOUT_TARGET_VALUE to 250.0),
            ctx,
        )
        cell(DataType.Type.WORKOUT_CADENCE_TARGET, StreamState.Idle, ctx) // no cadence target prescribed

        val power = cell(DataType.Type.POWER, streaming(DataType.Type.POWER, DataType.Field.POWER to 250.0), ctx)
        assertEquals("250/250", power.value) // composite

        val cadence = cell(DataType.Type.CADENCE, streaming(DataType.Type.CADENCE, DataType.Field.CADENCE to 88.0), ctx)
        assertEquals("88", cadence.value) // regular cadence field, no "/target"
        assertEquals(HudColor.GREEN, cadence.color) // regular cadence coloring (delta -2 from ideal 90)
    }

    @Test
    fun bandOnlyTargetDisplaysMidpoint() {
        val t = WorkoutTarget(value = null, min = 240.0, max = 260.0)
        assertTrue(t.isSet)
        assertEquals(250, t.displayValue)
    }

    @Test
    fun zeroTargetMeansNoTarget() {
        // Free-ride steps stream zeros — power must keep its plain value and zone coloring.
        val ctx = FormatContext()
        cell(
            DataType.Type.WORKOUT_POWER_TARGET,
            streaming(DataType.Type.WORKOUT_POWER_TARGET, DataType.Field.WORKOUT_TARGET_VALUE to 0.0),
            ctx,
        )
        val c = cell(DataType.Type.POWER, streaming(DataType.Type.POWER, DataType.Field.POWER to 150.0), ctx)
        assertEquals("150", c.value)
        assertEquals(HudColor.YELLOW, c.color) // 75% FTP → Z3 Tempo
    }

    @Test
    fun livePowerFallsBackToZonesWhenTargetStops() {
        val ctx = FormatContext()
        cell(DataType.Type.WORKOUT_POWER_TARGET, streaming(DataType.Type.WORKOUT_POWER_TARGET, DataType.Field.WORKOUT_TARGET_VALUE to 250.0), ctx)
        // Workout ends: the target stream goes Idle, which must clear the stashed target.
        cell(DataType.Type.WORKOUT_POWER_TARGET, StreamState.Idle, ctx)
        assertFalse(ctx.powerTarget!!.isSet)
        // 150 W at FTP 200 = 75% → Z3 Tempo → yellow under the default 7-band model.
        val c = cell(DataType.Type.POWER, streaming(DataType.Type.POWER, DataType.Field.POWER to 150.0), ctx)
        assertEquals(HudColor.YELLOW, c.color)
    }

    @Test
    fun workoutActiveGatesOnStepCount() {
        assertTrue(
            FieldFormat.workoutActive(
                streaming(DataType.Type.WORKOUT_INTERVAL_COUNT, DataType.Field.WORKOUT_STEP_COUNT to 12.0),
            ),
        )
        assertFalse(
            FieldFormat.workoutActive(
                streaming(DataType.Type.WORKOUT_INTERVAL_COUNT, DataType.Field.WORKOUT_STEP_COUNT to 0.0),
            ),
        )
        assertFalse(FieldFormat.workoutActive(StreamState.Idle))
    }
}
