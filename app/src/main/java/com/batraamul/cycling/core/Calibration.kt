package com.batraamul.cycling.core

import kotlin.math.abs
import kotlin.math.sqrt

enum class Confidence { NONE, LOW, MEDIUM, HIGH }

/** Summary of repeated one-step measurements. */
data class StepSummary(
    val n: Int,
    val meanM: Double,
    val medianM: Double,
    val sdM: Double,
    /** Coefficient of variation = sd / mean. Small = consistent measurements. */
    val cv: Double,
    /** The value we suggest saving: the median (robust to one bad measurement) when n ≥ 3. */
    val recommendedM: Double,
    val confidence: Confidence,
)

object StepCalibrationMath {
    val WALK_RANGE_M = 0.30..1.30
    val RUN_RANGE_M = 0.50..2.20

    fun summarize(measurementsM: List<Double>): StepSummary? {
        if (measurementsM.isEmpty()) return null
        val n = measurementsM.size
        val mean = measurementsM.average()
        val sorted = measurementsM.sorted()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val sd = if (n < 2) 0.0 else sqrt(measurementsM.sumOf { (it - mean) * (it - mean) } / (n - 1))
        val cv = if (mean > 0) sd / mean else 0.0
        val confidence = when {
            n >= 5 && cv <= 0.05 -> Confidence.HIGH
            n >= 3 && cv <= 0.10 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return StepSummary(n, mean, median, sd, cv, if (n >= 3) median else mean, confidence)
    }

    /** Multi-step course: measured course length divided by steps taken. Averages out step-to-step variation. */
    fun fromCourse(courseLengthM: Double, steps: Int): Double? =
        if (courseLengthM <= 0 || steps <= 0) null else courseLengthM / steps

    fun courseConfidence(steps: Int): Confidence = when {
        steps >= 60 -> Confidence.HIGH
        steps >= 20 -> Confidence.MEDIUM
        steps > 0 -> Confidence.LOW
        else -> Confidence.NONE
    }

    /** Height-based default used ONLY until the user saves a measured value. Common rule of thumb: 0.415 × height. */
    fun defaultFromHeight(heightCm: Int): Double = (heightCm * 0.415 / 100.0).coerceIn(WALK_RANGE_M)

    /** Walking distance (km) = N × d / 1000. */
    fun walkingDistanceKm(steps: Long, stepLengthM: Double): Double = steps * stepLengthM / 1000.0
}

enum class GpsQuality { GOOD, WARNING, REJECTED }

data class GpsCalibrationEvaluation(
    val rawKm: Double,
    val referenceKm: Double,
    /** k = Dr / Dm */
    val factor: Double,
    val quality: GpsQuality,
    val reasons: List<String>,
    /** True when the raw GPS distance was already within 1 %: a correction is unnecessary. */
    val correctionNegligible: Boolean,
)

object GpsCalibrationMath {
    const val MAX_FACTOR_DEVIATION = 0.20   // |k − 1| above this = reject (wrong course or bad trace)
    const val WARN_FACTOR_DEVIATION = 0.08
    const val MAX_MEAN_ACCURACY_M = 20.0
    const val WARN_MEAN_ACCURACY_M = 10.0
    const val MIN_FIXES = 30
    const val WARN_FIXES = 60

    fun evaluate(rawKm: Double, referenceKm: Double, meanAccuracyM: Double, fixCount: Int): GpsCalibrationEvaluation {
        val reasons = mutableListOf<String>()
        if (rawKm <= 0.0 || referenceKm <= 0.0) {
            return GpsCalibrationEvaluation(rawKm, referenceKm, 1.0, GpsQuality.REJECTED,
                listOf("No distance was recorded."), false)
        }
        val k = referenceKm / rawKm
        val dev = abs(k - 1.0)
        var quality = GpsQuality.GOOD

        fun warn(msg: String) { reasons += msg; if (quality == GpsQuality.GOOD) quality = GpsQuality.WARNING }
        fun reject(msg: String) { reasons += msg; quality = GpsQuality.REJECTED }

        when {
            fixCount < MIN_FIXES -> reject("Only $fixCount usable GPS fixes (need at least $MIN_FIXES).")
            fixCount < WARN_FIXES -> warn("Few GPS fixes ($fixCount). Ride the full course at a steady pace.")
        }
        when {
            meanAccuracyM.isNaN() || meanAccuracyM > MAX_MEAN_ACCURACY_M ->
                reject("GPS accuracy was poor (average ±${fmt(meanAccuracyM)} m). Try an open area away from buildings and trees.")
            meanAccuracyM > WARN_MEAN_ACCURACY_M -> warn("GPS accuracy was moderate (average ±${fmt(meanAccuracyM)} m).")
        }
        when {
            dev > MAX_FACTOR_DEVIATION ->
                reject("GPS measured ${fmt3(rawKm)} km — more than ${(MAX_FACTOR_DEVIATION * 100).toInt()} % off. Check the course length or GPS reception; a correction this large would be unreliable.")
            dev > WARN_FACTOR_DEVIATION -> warn("The correction is large (${fmtPct(k)}). Repeat the calibration to confirm it.")
        }
        return GpsCalibrationEvaluation(rawKm, referenceKm, k, quality, reasons, dev < 0.01)
    }

    /** Combines several confirmed calibrations: the active factor is their mean; spread shows consistency. */
    fun combine(factors: List<Double>): Pair<Double, Double>? {
        if (factors.isEmpty()) return null
        val mean = factors.average()
        val spread = (factors.maxOrNull()!! - factors.minOrNull()!!)
        return mean to spread
    }

    fun confidence(quality: GpsQuality?, confirmedCount: Int, spread: Double?): Confidence = when {
        quality == null || confirmedCount == 0 -> Confidence.NONE
        confirmedCount >= 2 && (spread ?: 1.0) <= 0.03 && quality == GpsQuality.GOOD -> Confidence.HIGH
        quality == GpsQuality.GOOD -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    private fun fmt(v: Double) = if (v.isNaN()) "?" else String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun fmtPct(k: Double) = String.format(java.util.Locale.US, "%+.1f %%", (k - 1) * 100)
}
