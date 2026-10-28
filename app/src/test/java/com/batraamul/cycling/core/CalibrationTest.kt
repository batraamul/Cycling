package com.batraamul.cycling.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test
    fun walkingDistanceFormula() {
        // N = 10 000 steps, d = 0.72 m  ->  7.2 km
        assertEquals(7.2, StepCalibrationMath.walkingDistanceKm(10_000, 0.72), 1e-9)
    }

    @Test
    fun summaryUsesMedianWhenThreeOrMore() {
        val s = StepCalibrationMath.summarize(listOf(0.70, 0.72, 0.71, 0.95, 0.71))!!
        assertEquals(0.71, s.recommendedM, 1e-9)       // the 0.95 outlier does not drag it
        assertEquals(5, s.n)
    }

    @Test
    fun consistentMeasurementsGiveHighConfidence() {
        val s = StepCalibrationMath.summarize(listOf(0.70, 0.71, 0.70, 0.72, 0.71))!!
        assertEquals(Confidence.HIGH, s.confidence)
    }

    @Test
    fun courseModeDividesLengthBySteps() {
        assertEquals(0.75, StepCalibrationMath.fromCourse(30.0, 40)!!, 1e-9)
        assertNull(StepCalibrationMath.fromCourse(30.0, 0))
    }

    @Test
    fun goodGpsCalibrationProducesSmallFactor() {
        val e = GpsCalibrationMath.evaluate(rawKm = 1.030, referenceKm = 1.000, meanAccuracyM = 4.5, fixCount = 220)
        assertEquals(GpsQuality.GOOD, e.quality)
        assertEquals(1.0 / 1.03, e.factor, 1e-9)
        assertFalse(e.correctionNegligible)
    }

    @Test
    fun poorAccuracyIsRejected() {
        val e = GpsCalibrationMath.evaluate(1.02, 1.0, meanAccuracyM = 28.0, fixCount = 200)
        assertEquals(GpsQuality.REJECTED, e.quality)
    }

    @Test
    fun hugeCorrectionIsRejectedEvenWithGoodAccuracy() {
        // GPS says 1.40 km on a 1 km course: never auto-apply k = 0.71.
        val e = GpsCalibrationMath.evaluate(1.40, 1.0, meanAccuracyM = 4.0, fixCount = 250)
        assertEquals(GpsQuality.REJECTED, e.quality)
    }

    @Test
    fun moderateCorrectionWarns() {
        val e = GpsCalibrationMath.evaluate(1.12, 1.0, 5.0, 200)
        assertEquals(GpsQuality.WARNING, e.quality)
        assertTrue(e.reasons.isNotEmpty())
    }

    @Test
    fun negligibleCorrectionIsFlagged() {
        assertTrue(GpsCalibrationMath.evaluate(1.004, 1.0, 4.0, 200).correctionNegligible)
    }

    @Test
    fun combineAveragesFactors() {
        val (mean, spread) = GpsCalibrationMath.combine(listOf(0.97, 0.99))!!
        assertEquals(0.98, mean, 1e-9)
        assertEquals(0.02, spread, 1e-9)
    }

    @Test
    fun cyclingCaloriesUseSpeedBasedMet() {
        // 20 km in 1 h (20 km/h → MET 8.0), 70 kg → 560 kcal
        assertEquals(560.0, Calories.cyclingKcal(70.0, 20_000.0, 3_600_000), 1e-6)
    }
}
