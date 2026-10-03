package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.RideState

/**
 * Whether a ride profile is up on the Karoo but recording hasn't started — the rider is looking at
 * live data fields before the ride, and the glasses should mirror them instead of "waiting for ride".
 *
 * The Karoo emits ActiveRideProfile when a profile is selected and ActiveRidePage as its pages are
 * shown, but nothing when the rider backs out to the launcher. So a profile/page event arms the
 * pre-ride view, and a ride ending (recording/paused → idle, which drops the rider into the ride
 * summary) disarms it until a profile is shown again.
 */
object PreRide {
    sealed interface Signal {
        /** A ride profile was selected, or one of its pages became visible. */
        data object ProfileShown : Signal

        /** The Karoo's ride state changed. */
        data class Ride(val state: RideState) : Signal
    }

    data class State(
        val profileShown: Boolean = false,
        val ride: RideState = RideState.Idle,
    ) {
        /** Show the data fields though nothing is recording yet. */
        val active: Boolean get() = profileShown && ride is RideState.Idle
    }

    fun reduce(state: State, signal: Signal): State = when (signal) {
        Signal.ProfileShown -> state.copy(profileShown = true)
        is Signal.Ride -> {
            val rideEnded = state.ride !is RideState.Idle && signal.state is RideState.Idle
            State(profileShown = state.profileShown && !rideEnded, ride = signal.state)
        }
    }
}
