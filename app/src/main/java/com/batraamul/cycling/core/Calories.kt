package com.batraamul.cycling.core

/**
 * Calorie ESTIMATES. These are approximations, not measurements: real energy use
 * depends on fitness, terrain, wind, bike and body composition.
 */
object Calories {
    /**
     * MET values for cycling by average speed, from the Compendium of Physical
     * Activities (Ainsworth et al., 2011). kcal = MET × body mass (kg) × hours.
     */
    fun cyclingMet(avgSpeedKmh: Double): Double = when {
        avgSpeedKmh < 16.0 -> 4.0
        avgSpeedKmh < 19.3 -> 6.8
        avgSpeedKmh < 22.5 -> 8.0
        avgSpeedKmh < 25.7 -> 10.0
        avgSpeedKmh < 30.6 -> 12.0
        else -> 15.8
    }

    fun cyclingKcal(weightKg: Double, distanceM: Double, movingMs: Long): Double {
        if (movingMs <= 0 || distanceM <= 0) return 0.0
        val hours = movingMs / 3_600_000.0
        val kmh = (distanceM / 1000.0) / hours
        return cyclingMet(kmh) * weightKg * hours
    }

    /** Walking: roughly 0.5 kcal per kg of body mass per km (net walking cost, a common approximation). */
    fun walkingKcal(weightKg: Double, distanceM: Double): Double = 0.5 * weightKg * distanceM / 1000.0
}
