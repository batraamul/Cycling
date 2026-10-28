package com.batraamul.cycling.core

/**
 * The Tracking Mode Controller's rules, as a pure state machine.
 *
 * There is exactly ONE mode at a time. Walking and cycling can never both add to totals,
 * because every component asks this state "should I count steps?" / "should I record GPS?"
 * and the answers are mutually exclusive by construction:
 *
 *     countsSteps  == (mode == WALKING)
 *     recordsGps   == (mode == CYCLING && !paused && !interrupted)
 *
 *            Start Cycling                Finish / Discard
 *   WALKING ───────────────▶ CYCLING ───────────────────────▶ WALKING (or IDLE if walking disabled)
 *      ▲                     │  ▲ Pause/Resume
 *      └── Enable walking ── IDLE   Interrupted/Recovered
 */
enum class TrackingMode { IDLE, WALKING, CYCLING }

data class ModeState(
    val mode: TrackingMode = TrackingMode.IDLE,
    val walkingEnabled: Boolean = true,
    val activeRideId: Long? = null,
    val ridePaused: Boolean = false,
    /** GPS could not be (re)started, e.g. after the phone rebooted mid-ride. Needs the user. */
    val rideInterrupted: Boolean = false,
) {
    val countsSteps: Boolean get() = mode == TrackingMode.WALKING
    val recordsGps: Boolean get() = mode == TrackingMode.CYCLING && !ridePaused && !rideInterrupted
}

sealed interface ModeEvent {
    data class StartCycling(val rideId: Long) : ModeEvent
    data object PauseRide : ModeEvent
    data object ResumeRide : ModeEvent
    data object FinishRide : ModeEvent
    data object DiscardRide : ModeEvent
    data object EnableWalking : ModeEvent
    data object DisableWalking : ModeEvent
    data object RideInterrupted : ModeEvent
    data object RideRecovered : ModeEvent
}

sealed interface Transition {
    data class Ok(val state: ModeState) : Transition
    data class Rejected(val reason: String) : Transition
}

object TrackingStateMachine {
    fun reduce(s: ModeState, e: ModeEvent): Transition = when (e) {
        is ModeEvent.StartCycling ->
            if (s.mode == TrackingMode.CYCLING) Transition.Rejected("A ride is already in progress.")
            else Transition.Ok(s.copy(mode = TrackingMode.CYCLING, activeRideId = e.rideId, ridePaused = false, rideInterrupted = false))

        ModeEvent.PauseRide -> when {
            s.mode != TrackingMode.CYCLING -> Transition.Rejected("No ride in progress.")
            s.ridePaused -> Transition.Rejected("The ride is already paused.")
            else -> Transition.Ok(s.copy(ridePaused = true))   // mode stays CYCLING: walking is NOT resumed
        }

        ModeEvent.ResumeRide -> when {
            s.mode != TrackingMode.CYCLING -> Transition.Rejected("No ride in progress.")
            !s.ridePaused -> Transition.Rejected("The ride is not paused.")
            else -> Transition.Ok(s.copy(ridePaused = false))
        }

        ModeEvent.FinishRide, ModeEvent.DiscardRide ->
            if (s.mode != TrackingMode.CYCLING) Transition.Rejected("No ride in progress.")
            else Transition.Ok(afterRide(s))

        ModeEvent.EnableWalking ->
            if (s.mode == TrackingMode.CYCLING) Transition.Ok(s.copy(walkingEnabled = true)) // takes effect after the ride
            else Transition.Ok(s.copy(walkingEnabled = true, mode = TrackingMode.WALKING))

        ModeEvent.DisableWalking ->
            if (s.mode == TrackingMode.CYCLING) Transition.Ok(s.copy(walkingEnabled = false))
            else Transition.Ok(s.copy(walkingEnabled = false, mode = TrackingMode.IDLE))

        ModeEvent.RideInterrupted ->
            if (s.mode != TrackingMode.CYCLING) Transition.Rejected("No ride in progress.")
            else Transition.Ok(s.copy(rideInterrupted = true))

        ModeEvent.RideRecovered ->
            if (s.mode != TrackingMode.CYCLING) Transition.Rejected("No ride in progress.")
            else Transition.Ok(s.copy(rideInterrupted = false))
    }

    private fun afterRide(s: ModeState) = s.copy(
        mode = if (s.walkingEnabled) TrackingMode.WALKING else TrackingMode.IDLE,
        activeRideId = null, ridePaused = false, rideInterrupted = false,
    )
}
