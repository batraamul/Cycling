package com.batraamul.cycling.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Counts steps from raw accelerometer samples. Used ONLY when the phone has no
 * hardware step-counter sensor (the hardware counter is far more battery-efficient).
 *
 * How it works (one sample at a time, ~50 samples per second):
 *  1. Magnitude: |a| = sqrt(x² + y² + z²). Using the magnitude makes the result
 *     independent of how the phone sits in a pocket or hand.
 *  2. Gravity removal: a slow moving average of |a| (≈ 2 s) estimates gravity
 *     (~9.81 m/s²). Subtracting it leaves only the body's motion.
 *  3. Smoothing: a fast moving average (≈ 50 ms) removes sensor noise.
 *  4. Peak detection: every step produces one acceleration peak. A peak counts as a
 *     "candidate" when it is above a threshold, the signal dipped below the baseline
 *     since the last peak (hysteresis), and at least [minStepIntervalNs] has passed.
 *  5. Regularity gate: real walking is rhythmic; shaking a phone or tapping a table is
 *     not. Candidates are only counted after [stepsToConfirm] arrive in a regular rhythm.
 *     The buffered candidates are then counted retroactively, so no real steps are lost.
 *
 * Timestamps come from the sensor event (nanoseconds), so batched/delayed delivery
 * from the sensor hardware FIFO gives exactly the same result as live delivery.
 */
class AccelerometerStepDetector(
    private val minThreshold: Double = 0.9,                // m/s² above gravity
    private val minStepIntervalNs: Long = 260_000_000L,     // ≤ ~3.8 steps/s (running)
    private val maxStepIntervalNs: Long = 2_000_000_000L,   // slower than this = not walking
    private val stepsToConfirm: Int = 4,
    private val gravityTauS: Double = 2.0,
    private val smoothTauS: Double = 0.05,
) {
    private var lastTimestampNs = -1L
    private var gravity = Double.NaN
    private var smooth = 0.0
    private var prevSmooth = 0.0
    private var rising = false
    private var armed = true
    private var avgPeak = 0.0

    private var lastCandidateNs = -1L
    private var walking = false
    private var pending = 0

    /** Total steps confirmed since creation (diagnostics). */
    var totalSteps: Long = 0
        private set

    /** Feeds one sample; returns the number of steps confirmed by this sample (usually 0 or 1). */
    fun onSample(x: Float, y: Float, z: Float, timestampNs: Long): Int {
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        if (lastTimestampNs < 0 || gravity.isNaN()) {
            lastTimestampNs = timestampNs
            gravity = mag
            return 0
        }
        val dt = (timestampNs - lastTimestampNs) / 1e9
        if (dt <= 0.0) return 0                       // duplicate / out-of-order sample
        lastTimestampNs = timestampNs
        if (dt > 1.0) {                                // long gap (sensor paused): restart filters
            gravity = mag; smooth = 0.0; prevSmooth = 0.0; rising = false; armed = true
            resetRhythm()
            return 0
        }

        gravity += (mag - gravity) * (dt / (gravityTauS + dt))
        prevSmooth = smooth
        smooth += ((mag - gravity) - smooth) * (dt / (smoothTauS + dt))

        val threshold = max(minThreshold, 0.4 * avgPeak)
        if (smooth < 0.0) armed = true

        var confirmed = 0
        val wasRising = rising
        rising = smooth > prevSmooth
        // A peak = the signal was rising and has just started falling.
        if (wasRising && !rising && prevSmooth > threshold && armed) {
            armed = false
            avgPeak = if (avgPeak == 0.0) prevSmooth else avgPeak + (prevSmooth - avgPeak) * 0.2
            confirmed = onCandidate(timestampNs)
        }
        // Too long since the last candidate: the walk has stopped.
        if (lastCandidateNs >= 0 && timestampNs - lastCandidateNs > maxStepIntervalNs) resetRhythm()
        return confirmed
    }

    private fun onCandidate(t: Long): Int {
        if (lastCandidateNs >= 0 && t - lastCandidateNs < minStepIntervalNs) return 0 // too soon: bounce
        lastCandidateNs = t
        if (walking) {
            totalSteps++
            return 1
        }
        pending++
        if (pending >= stepsToConfirm) {
            walking = true
            val released = pending
            pending = 0
            totalSteps += released
            return released
        }
        return 0
    }

    private fun resetRhythm() {
        walking = false
        pending = 0
        lastCandidateNs = -1L
    }
}
