package com.batraamul.cycling.core

/** One GPS reading, in plain Kotlin types (no Android classes, so it can be unit-tested). */
data class GpsFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float? = null,
)

/**
 * Turns a stream of GPS fixes into a trustworthy ride distance.
 *
 * Raw GPS positions "wobble" by a few metres even when you stand still. Summing every
 * wobble would make a parked bicycle accumulate distance. Three filters prevent that:
 *
 *  1. Accuracy filter — fixes whose reported accuracy is worse than [maxAccuracyM] are ignored.
 *  2. Jump filter — a fix implying an impossible speed (> [speedLimitMps]) is ignored.
 *  3. Anchor (jitter) filter — distance is only added once the position has moved further
 *     from the last accepted point (the "anchor") than the combined GPS uncertainty.
 *     Small wobbles around the anchor are "held" and never added; real slow movement still
 *     accumulates because the displacement eventually exceeds the threshold.
 *  4. Stationary gate — when the receiver's Doppler speed says "not moving", position
 *     changes up to 30 m are treated as noise (stops drift while parked at a signal).
 *
 * The result is the RAW distance. A user calibration factor is applied separately and
 * the raw value is always kept, so it remains transparent and reversible.
 */
class DistanceAccumulator(
    private val maxAccuracyM: Float = 25f,
    private val speedLimitMps: Double = 25.0,       // 90 km/h — beyond any bicycle
    private val minStepM: Double = 3.0,
    private val maxStepM: Double = 15.0,
    private val movingSpeedMps: Double = 0.8,
) {
    enum class Result { FIRST, ACCEPTED, HELD, REJECTED_ACCURACY, REJECTED_SPEED, REJECTED_TIME }

    var rawDistanceM = 0.0; private set
    var movingMs = 0L; private set
    var topSpeedMps = 0.0; private set
    var acceptedFixes = 0; private set
    var rejectedFixes = 0; private set
    private var accuracySum = 0.0
    private var accuracyCount = 0

    private var anchor: GpsFix? = null
    private var lastValidTimeMs = -1L

    val meanAccuracyM: Double get() = if (accuracyCount == 0) Double.NaN else accuracySum / accuracyCount

    /** Restores totals saved in the database (after process recreation). The anchor is NOT restored: a gap follows. */
    fun restore(rawDistanceM: Double, movingMs: Long, topSpeedMps: Double, acceptedFixes: Int = 0, meanAccuracyM: Double? = null) {
        this.rawDistanceM = rawDistanceM
        this.movingMs = movingMs
        this.topSpeedMps = topSpeedMps
        this.acceptedFixes = acceptedFixes
        if (meanAccuracyM != null && !meanAccuracyM.isNaN() && acceptedFixes > 0) {
            accuracySum = meanAccuracyM * acceptedFixes
            accuracyCount = acceptedFixes
        }
        breakSegment()
    }

    /** Call on pause/resume or after an interruption: the next fix starts a new segment, so no straight line is drawn across the gap. */
    fun breakSegment() {
        anchor = null
        lastValidTimeMs = -1L
    }

    fun add(fix: GpsFix): Result {
        if (fix.accuracyM <= 0f || fix.accuracyM > maxAccuracyM) { rejectedFixes++; return Result.REJECTED_ACCURACY }
        val a = anchor
        if (a == null) {
            anchor = fix
            countValid(fix, moving = false)
            return Result.FIRST
        }
        val dtMs = fix.timeMs - a.timeMs
        if (dtMs <= 0) { rejectedFixes++; return Result.REJECTED_TIME }
        val d = Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon)
        val implied = d / (dtMs / 1000.0)
        val threshold = (0.8 * (a.accuracyM + fix.accuracyM) / 2.0).coerceIn(minStepM, maxStepM)
        if (implied > speedLimitMps && d > threshold) { rejectedFixes++; return Result.REJECTED_SPEED }

        // Stationary gate: GPS chips measure speed from the Doppler shift, independently of the
        // noisy position. If the receiver says "not moving", treat modest position changes as noise.
        val dopplerSaysStationary = fix.speedMps != null && fix.speedMps < movingSpeedMps && d < 2 * maxStepM
        return if (d >= threshold && !dopplerSaysStationary) {
            rawDistanceM += d
            anchor = fix
            countValid(fix, moving = true)
            Result.ACCEPTED
        } else {
            val speed = fix.speedMps?.toDouble() ?: 0.0
            countValid(fix, moving = speed >= movingSpeedMps)
            Result.HELD
        }
    }

    private fun countValid(fix: GpsFix, moving: Boolean) {
        acceptedFixes++
        accuracySum += fix.accuracyM
        accuracyCount++
        if (lastValidTimeMs >= 0 && moving) {
            movingMs += (fix.timeMs - lastValidTimeMs).coerceIn(0, 5_000)
        }
        lastValidTimeMs = fix.timeMs
        val s = fix.speedMps?.toDouble()
        if (s != null && fix.accuracyM <= 15f && s <= speedLimitMps && s > topSpeedMps) topSpeedMps = s
    }
}
