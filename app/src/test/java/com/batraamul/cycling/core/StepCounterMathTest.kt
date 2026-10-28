package com.batraamul.cycling.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterMathTest {
    @Test
    fun firstReadingOnlySetsBaseline() {
        val u = StepCounterMath.update(CounterBaseline(null, 5), raw = 18_000, bootCount = 5)
        assertEquals(0, u.newSteps)
        assertEquals(18_000L, u.baseline.lastRaw)
        assertEquals(CounterUpdate.Note.BASELINE_SET, u.note)
    }

    @Test
    fun normalReadingAddsDifference() {
        val u = StepCounterMath.update(CounterBaseline(18_000, 5), raw = 18_250, bootCount = 5)
        assertEquals(250, u.newSteps)
    }

    @Test
    fun replayingTheSameReadingAddsNothing() {
        // Simulates restoring after process death with the baseline saved alongside the total.
        val b = CounterBaseline(18_250, 5)
        assertEquals(0, StepCounterMath.update(b, 18_250, 5).newSteps)
    }

    @Test
    fun rebootDetectedByBootCount() {
        val u = StepCounterMath.update(CounterBaseline(18_250, 5), raw = 120, bootCount = 6)
        assertEquals(120, u.newSteps)
        assertEquals(CounterUpdate.Note.REBOOT_DETECTED, u.note)
    }

    @Test
    fun rebootDetectedByCounterGoingBackwards() {
        val u = StepCounterMath.update(CounterBaseline(18_250, null), raw = 40, bootCount = null)
        assertEquals(40, u.newSteps)
    }

    @Test
    fun implausibleJumpIsDiscardedAndRebaselined() {
        val u = StepCounterMath.update(CounterBaseline(100, 5), raw = 100 + StepCounterMath.MAX_PLAUSIBLE_DELTA + 1, bootCount = 5)
        assertEquals(0, u.newSteps)
        assertEquals(CounterUpdate.Note.ANOMALY_REBASELINED, u.note)
    }
}
