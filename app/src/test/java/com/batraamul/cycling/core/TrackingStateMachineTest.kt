package com.batraamul.cycling.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStateMachineTest {
    private fun ok(s: ModeState, e: ModeEvent): ModeState =
        (TrackingStateMachine.reduce(s, e) as Transition.Ok).state

    private val walking = ModeState(mode = TrackingMode.WALKING, walkingEnabled = true)

    @Test
    fun walkingAndCyclingAreMutuallyExclusive() {
        val cycling = ok(walking, ModeEvent.StartSession(1))
        assertTrue(cycling.recordsGps)
        assertFalse(cycling.countsSteps)
        assertTrue(walking.countsSteps)
        assertFalse(walking.recordsGps)
    }

    @Test
    fun pausingARideDoesNotResumeWalking() {
        val paused = ok(ok(walking, ModeEvent.StartSession(1)), ModeEvent.PauseRide)
        assertFalse(paused.countsSteps)
        assertFalse(paused.recordsGps)
        assertEquals(TrackingMode.SESSION, paused.mode)
    }

    @Test
    fun finishingReturnsToWalking() {
        val after = ok(ok(walking, ModeEvent.StartSession(1)), ModeEvent.FinishRide)
        assertEquals(TrackingMode.WALKING, after.mode)
        assertNull(after.activeRideId)
        assertTrue(after.countsSteps)
    }

    @Test
    fun finishingWithWalkingDisabledGoesIdle() {
        val c = ok(ok(walking, ModeEvent.StartSession(1)), ModeEvent.DisableWalking)
        assertEquals(TrackingMode.IDLE, ok(c, ModeEvent.FinishRide).mode)
    }

    @Test
    fun cannotStartASecondRide() {
        val c = ok(walking, ModeEvent.StartSession(1))
        assertTrue(TrackingStateMachine.reduce(c, ModeEvent.StartSession(2)) is Transition.Rejected)
    }

    @Test
    fun interruptedRideNeitherCountsStepsNorRecordsGps() {
        val i = ok(ok(walking, ModeEvent.StartSession(1)), ModeEvent.RideInterrupted)
        assertFalse(i.countsSteps)   // never silently count cycling as walking
        assertFalse(i.recordsGps)
        assertTrue(ok(i, ModeEvent.RideRecovered).recordsGps)
    }

    @Test
    fun finishOrPauseWithoutRideIsRejected() {
        assertTrue(TrackingStateMachine.reduce(walking, ModeEvent.FinishRide) is Transition.Rejected)
        assertTrue(TrackingStateMachine.reduce(walking, ModeEvent.PauseRide) is Transition.Rejected)
    }

    @Test
    fun pausingStepsStopsCountingButStaysInWalkingMode() {
        val p = ok(walking, ModeEvent.PauseSteps(untilMs = 5_000))
        assertEquals(TrackingMode.WALKING, p.mode)
        assertTrue(p.stepsPaused)
        assertFalse(p.countsSteps)
        assertFalse(p.recordsGps)
        assertTrue(ok(p, ModeEvent.ResumeSteps).countsSteps)
    }

    @Test
    fun stepPauseOnlyAllowedWhileWalking() {
        val c = ok(walking, ModeEvent.StartSession(1))
        assertTrue(TrackingStateMachine.reduce(c, ModeEvent.PauseSteps(PAUSED_INDEFINITELY)) is Transition.Rejected)
        assertTrue(TrackingStateMachine.reduce(walking, ModeEvent.ResumeSteps) is Transition.Rejected)
    }

    @Test
    fun startingARideClearsAStepPause() {
        val p = ok(walking, ModeEvent.PauseSteps(PAUSED_INDEFINITELY))
        val afterRide = ok(ok(p, ModeEvent.StartSession(1)), ModeEvent.FinishRide)
        assertTrue(afterRide.countsSteps)
        assertNull(afterRide.stepsPausedUntilMs)
    }

    @Test
    fun sensorsFollowTheActivityType() {
        fun st(t: ActivityType) = ok(walking, ModeEvent.StartSession(1, t))
        assertTrue(st(ActivityType.CYCLING).recordsGps); assertFalse(st(ActivityType.CYCLING).countsSessionSteps)
        assertTrue(st(ActivityType.RUNNING).recordsGps); assertTrue(st(ActivityType.RUNNING).countsSessionSteps)
        assertFalse(st(ActivityType.TREADMILL).recordsGps); assertTrue(st(ActivityType.TREADMILL).countsSessionSteps)
        assertFalse(st(ActivityType.EXERCISE).recordsGps); assertFalse(st(ActivityType.EXERCISE).countsSessionSteps)
        // Daily walking never counts during any session.
        ActivityType.entries.forEach { assertFalse(st(it).countsSteps) }
        // Pausing stops session steps too.
        assertFalse(ok(st(ActivityType.RUNNING), ModeEvent.PauseRide).countsSessionSteps)
    }
}
