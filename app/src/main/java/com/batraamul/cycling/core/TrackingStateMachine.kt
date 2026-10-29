package com.batraamul.cycling.core

/**
 * The Tracking Mode Controller's rules, as a pure state machine.
 *
 * There is exactly ONE mode at a time. Daily walking and a recorded activity session (cycling,
 * running, jogging, treadmill, exercise) can never both add to totals, because every component asks
 * this state what to record and the answers are mutually exclusive by construction:
 *
 *     countsSteps         == (mode == WALKING && steps not paused)          → daily walking steps
 *     countsSessionSteps  == (mode == SESSION && type counts steps && live)  → that session's steps
 *     recordsGps          == (mode == SESSION && type uses GPS && live)
 *
 *            Start activity               Finish / Discard
 *   WALKING ───────────────▶ SESSION ───────────────────────▶ WALKING (or IDLE if walking disabled)
 *      ▲                     │  ▲ Pause/Resume
 *      └── Enable walking ── IDLE   Interrupted/Recovered (GPS activities only)
 *
 * (Before v1.2.0 the SESSION mode was called CYCLING; the old stored value is still understood.)
 */
enum class TrackingMode { IDLE, WALKING, SESSION }

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
    /** Kind of the active session (null when no session is active). */
    val sessionType: ActivityType? = null,
) {
    val inSession: Boolean get() = mode == TrackingMode.SESSION
    private val sessionLive: Boolean get() = inSession && !ridePaused && !rideInterrupted
    val stepsPaused: Boolean get() = mode == TrackingMode.WALKING && stepsPausedUntilMs != null
    val countsSteps: Boolean get() = mode == TrackingMode.WALKING && stepsPausedUntilMs == null
    val countsSessionSteps: Boolean get() = sessionLive && (sessionType ?: ActivityType.CYCLING).countsSteps
    val recordsGps: Boolean get() = sessionLive && (sessionType ?: ActivityType.CYCLING).usesGps
}

const val PAUSED_INDEFINITELY = Long.MAX_VALUE

sealed interface ModeEvent {
    /** Pause ordinary step counting until [untilMs] (or [PAUSED_INDEFINITELY]). */
    data class PauseSteps(val untilMs: Long) : ModeEvent
    data object ResumeSteps : ModeEvent
    data class StartSession(val rideId: Long, val type: ActivityType = ActivityType.CYCLING) : ModeEvent
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
    private const val NO_SESSION = "No activity in progress."

    fun reduce(s: ModeState, e: ModeEvent): Transition = when (e) {
        is ModeEvent.PauseSteps -> when {
            s.mode != TrackingMode.WALKING -> Transition.Rejected("Step counting is not active.")
            else -> Transition.Ok(s.copy(stepsPausedUntilMs = e.untilMs))   // a new pause replaces an existing one
        }

        ModeEvent.ResumeSteps ->
            if (!s.stepsPaused) Transition.Rejected("Step counting is not paused.")
            else Transition.Ok(s.copy(stepsPausedUntilMs = null))

        is ModeEvent.StartSession ->
            if (s.inSession) Transition.Rejected("An activity is already in progress. Finish it first.")
            else Transition.Ok(s.copy(mode = TrackingMode.SESSION, activeRideId = e.rideId, ridePaused = false, rideInterrupted = false,
                stepsPausedUntilMs = null, sessionType = e.type))

        ModeEvent.PauseRide -> when {
            !s.inSession -> Transition.Rejected(NO_SESSION)
            s.ridePaused -> Transition.Rejected("The activity is already paused.")
            else -> Transition.Ok(s.copy(ridePaused = true))   // mode stays SESSION: walking is NOT resumed
        }

        ModeEvent.ResumeRide -> when {
            !s.inSession -> Transition.Rejected(NO_SESSION)
            !s.ridePaused -> Transition.Rejected("The activity is not paused.")
            else -> Transition.Ok(s.copy(ridePaused = false))
        }

        ModeEvent.FinishRide, ModeEvent.DiscardRide ->
            if (!s.inSession) Transition.Rejected(NO_SESSION)
            else Transition.Ok(afterSession(s))

        ModeEvent.EnableWalking ->
            if (s.inSession) Transition.Ok(s.copy(walkingEnabled = true)) // takes effect after the session
            else Transition.Ok(s.copy(walkingEnabled = true, mode = TrackingMode.WALKING))

        ModeEvent.DisableWalking ->
            if (s.inSession) Transition.Ok(s.copy(walkingEnabled = false))
            else Transition.Ok(s.copy(walkingEnabled = false, mode = TrackingMode.IDLE, stepsPausedUntilMs = null))

        ModeEvent.RideInterrupted ->
            if (!s.inSession) Transition.Rejected(NO_SESSION)
            else Transition.Ok(s.copy(rideInterrupted = true))

        ModeEvent.RideRecovered ->
            if (!s.inSession) Transition.Rejected(NO_SESSION)
            else Transition.Ok(s.copy(rideInterrupted = false))
    }

    private fun afterSession(s: ModeState) = s.copy(
        mode = if (s.walkingEnabled) TrackingMode.WALKING else TrackingMode.IDLE,
        activeRideId = null, ridePaused = false, rideInterrupted = false, stepsPausedUntilMs = null, sessionType = null,
    )
}
