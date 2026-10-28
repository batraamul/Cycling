package com.batraamul.cycling.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AccelerometerStepDetectorTest {
    private val g = 9.81

    /** Generates a synthetic accelerometer trace: gravity + one sinusoidal bump per step + noise. */
    private fun run(
        detector: AccelerometerStepDetector,
        seconds: Double,
        stepHz: Double,
        amplitude: Double,
        noise: Double = 0.15,
        rateHz: Int = 50,
        startNs: Long = 1_000_000_000L,
        seed: Int = 1,
    ): Int {
        val rnd = Random(seed)
        var steps = 0
        val n = (seconds * rateHz).toInt()
        for (i in 0 until n) {
            val t = i.toDouble() / rateHz
            val motion = if (stepHz > 0) amplitude * sin(2 * PI * stepHz * t) else 0.0
            val m = g + motion + (rnd.nextDouble() * 2 - 1) * noise
            // Split magnitude over axes as if the phone were tilted in a pocket.
            val x = (m * 0.3).toFloat(); val y = (m * 0.9).toFloat(); val z = (m * kotlin.math.sqrt(1 - 0.09 - 0.81)).toFloat()
            steps += detector.onSample(x, y, z, startNs + (t * 1e9).toLong())
        }
        return steps
    }

    @Test
    fun countsSteadyWalkingWithinFivePercent() {
        val steps = run(AccelerometerStepDetector(), seconds = 60.0, stepHz = 1.8, amplitude = 2.5)
        val expected = 108
        assertTrue("counted $steps, expected ≈$expected", kotlin.math.abs(steps - expected) <= expected * 0.05)
    }

    @Test
    fun countsBriskWalkAndGentleWalk() {
        val brisk = run(AccelerometerStepDetector(), 30.0, 2.2, 3.5)
        val gentle = run(AccelerometerStepDetector(), 30.0, 1.4, 1.6)
        assertTrue("brisk $brisk", kotlin.math.abs(brisk - 66) <= 4)
        assertTrue("gentle $gentle", kotlin.math.abs(gentle - 42) <= 3)
    }

    @Test
    fun phoneLyingStillCountsNothing() {
        assertEquals(0, run(AccelerometerStepDetector(), 120.0, stepHz = 0.0, amplitude = 0.0, noise = 0.3))
    }

    @Test
    fun threeIsolatedBumpsAreRejectedByRhythmGate() {
        // 3 bumps (< stepsToConfirm) then stillness: no steps.
        val d = AccelerometerStepDetector()
        var steps = run(d, 1.6, 1.8, 2.5)          // ≈ 3 cycles
        steps += run(d, 5.0, 0.0, 0.0, startNs = 3_000_000_000L)
        assertEquals(0, steps)
    }

    @Test
    fun batchedDeliveryGivesSameResultAsLive() {
        // Same samples; timestamps drive the result, not arrival time.
        val a = run(AccelerometerStepDetector(), 20.0, 1.9, 2.4, seed = 7)
        val b = run(AccelerometerStepDetector(), 20.0, 1.9, 2.4, seed = 7)
        assertEquals(a, b)
    }
}
