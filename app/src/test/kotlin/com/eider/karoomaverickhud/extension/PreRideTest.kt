package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.extension.PreRide.Signal
import io.hammerhead.karooext.models.RideState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies when the glasses show live fields before recording starts. */
class PreRideTest {

    private fun run(vararg signals: Signal): PreRide.State =
        signals.fold(PreRide.State()) { s, sig -> PreRide.reduce(s, sig) }

    @Test
    fun idleWithoutProfileWaits() {
        assertFalse(run(Signal.Ride(RideState.Idle)).active)
    }

    @Test
    fun selectingAProfileShowsFieldsPreRide() {
        assertTrue(run(Signal.Ride(RideState.Idle), Signal.ProfileShown).active)
    }

    @Test
    fun recordingIsNotPreRide() {
        // The HUD shows anyway while recording/paused; preRide is only the idle case.
        val s = run(Signal.ProfileShown, Signal.Ride(RideState.Recording))
        assertFalse(s.active)
        assertFalse(PreRide.reduce(s, Signal.Ride(RideState.Paused(auto = false))).active)
    }

    @Test
    fun endingTheRideReturnsToWaiting() {
        val s = run(Signal.ProfileShown, Signal.Ride(RideState.Recording), Signal.Ride(RideState.Idle))
        assertFalse("ride summary / launcher after a ride: back to waiting", s.active)
    }

    @Test
    fun aProfileShownAfterTheRideArmsAgain() {
        val s = run(
            Signal.ProfileShown, Signal.Ride(RideState.Recording), Signal.Ride(RideState.Idle),
            Signal.ProfileShown,
        )
        assertTrue(s.active)
    }

    @Test
    fun repeatedIdleDoesNotDisarm() {
        // Idle → Idle isn't a ride ending (e.g. a replayed state on resubscribe).
        assertTrue(run(Signal.ProfileShown, Signal.Ride(RideState.Idle), Signal.Ride(RideState.Idle)).active)
    }
}
