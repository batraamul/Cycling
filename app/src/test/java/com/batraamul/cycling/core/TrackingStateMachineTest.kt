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
        val cycling = ok(walking, ModeEvent.StartCycling(1))
        assertTrue(cycling.recordsGps)
        assertFalse(cycling.countsSteps)
        assertTrue(walking.countsSteps)
        assertFalse(walking.recordsGps)
    }

    @Test
    fun pausingARideDoesNotResumeWalking() {
        val paused = ok(ok(walking, ModeEvent.StartCycling(1)), ModeEvent.PauseRide)
        assertFalse(paused.countsSteps)
        assertFalse(paused.recordsGps)
        assertEquals(TrackingMode.CYCLING, paused.mode)
    }

    @Test
    fun finishingReturnsToWalking() {
        val after = ok(ok(walking, ModeEvent.StartCycling(1)), ModeEvent.FinishRide)
        assertEquals(TrackingMode.WALKING, after.mode)
        assertNull(after.activeRideId)
        assertTrue(after.countsSteps)
    }

    @Test
    fun finishingWithWalkingDisabledGoesIdle() {
        val c = ok(ok(walking, ModeEvent.StartCycling(1)), ModeEvent.DisableWalking)
        assertEquals(TrackingMode.IDLE, ok(c, ModeEvent.FinishRide).mode)
    }

    @Test
    fun cannotStartASecondRide() {
        val c = ok(walking, ModeEvent.StartCycling(1))
        assertTrue(TrackingStateMachine.reduce(c, ModeEvent.StartCycling(2)) is Transition.Rejected)
    }

    @Test
    fun interruptedRideNeitherCountsStepsNorRecordsGps() {
        val i = ok(ok(walking, ModeEvent.StartCycling(1)), ModeEvent.RideInterrupted)
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
        val c = ok(walking, ModeEvent.StartCycling(1))
        assertTrue(TrackingStateMachine.reduce(c, ModeEvent.PauseSteps(PAUSED_INDEFINITELY)) is Transition.Rejected)
        assertTrue(TrackingStateMachine.reduce(walking, ModeEvent.ResumeSteps) is Transition.Rejected)
    }

    @Test
    fun startingARideClearsAStepPause() {
        val p = ok(walking, ModeEvent.PauseSteps(PAUSED_INDEFINITELY))
        val afterRide = ok(ok(p, ModeEvent.StartCycling(1)), ModeEvent.FinishRide)
        assertTrue(afterRide.countsSteps)
        assertNull(afterRide.stepsPausedUntilMs)
    }
}
