package com.batraamul.cycling.core

/**
 * The Tracking Mode Controller's rules, as a pure state machine.
 *
 * There is exactly ONE mode at a time. Walking and cycling can never both add to totals,
 * because every component asks this state "should I count steps?" / "should I record GPS?"
 * and the answers are mutually exclusive by construction:
 *
 *     countsSteps  == (mode == WALKING && steps not paused)
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
    /**
     * Walking mode, but step counting temporarily paused (e.g. riding a motorbike, where engine
     * vibration would be counted as steps). Value = resume time in epoch ms; [PAUSED_INDEFINITELY]
     * means "until the user resumes". null = not paused.
     */
    val stepsPausedUntilMs: Long? = null,
) {
    val stepsPaused: Boolean get() = mode == TrackingMode.WALKING && stepsPausedUntilMs != null
    val countsSteps: Boolean get() = mode == TrackingMode.WALKING && stepsPausedUntilMs == null
    val recordsGps: Boolean get() = mode == TrackingMode.CYCLING && !ridePaused && !rideInterrupted
}

const val PAUSED_INDEFINITELY = Long.MAX_VALUE

sealed interface ModeEvent {
    /** Pause ordinary step counting until [untilMs] (or [PAUSED_INDEFINITELY]). */
    data class PauseSteps(val untilMs: Long) : ModeEvent
    data object ResumeSteps : ModeEvent
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
        is ModeEvent.PauseSteps -> when {
            s.mode != TrackingMode.WALKING -> Transition.Rejected("Step counting is not active.")
            else -> Transition.Ok(s.copy(stepsPausedUntilMs = e.untilMs))   // a new pause replaces an existing one
        }

        ModeEvent.ResumeSteps ->
            if (!s.stepsPaused) Transition.Rejected("Step counting is not paused.")
            else Transition.Ok(s.copy(stepsPausedUntilMs = null))

        is ModeEvent.StartCycling ->
            if (s.mode == TrackingMode.CYCLING) Transition.Rejected("A ride is already in progress.")
            else Transition.Ok(s.copy(mode = TrackingMode.CYCLING, activeRideId = e.rideId, ridePaused = false, rideInterrupted = false,
                stepsPausedUntilMs = null))

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
            else Transition.Ok(s.copy(walkingEnabled = false, mode = TrackingMode.IDLE, stepsPausedUntilMs = null))

        ModeEvent.RideInterrupted ->
            if (s.mode != TrackingMode.CYCLING) Transition.Rejected("No ride in progress.")
            else Transition.Ok(s.copy(rideInterrupted = true))

        ModeEvent.RideRecovered ->
            if (s.mode != TrackingMode.CYCLING) Transition.Rejected("No ride in progress.")
            else Transition.Ok(s.copy(rideInterrupted = false))
    }

    private fun afterRide(s: ModeState) = s.copy(
        mode = if (s.walkingEnabled) TrackingMode.WALKING else TrackingMode.IDLE,
        activeRideId = null, ridePaused = false, rideInterrupted = false, stepsPausedUntilMs = null,
    )
}
