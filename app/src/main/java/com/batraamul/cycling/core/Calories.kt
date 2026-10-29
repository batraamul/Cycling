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

    /**
     * Running, jogging and treadmill: about 1 kcal per kg of body mass per km, almost independent
     * of speed (a well-known rule of thumb for gross running cost).
     */
    fun runningKcal(weightKg: Double, distanceM: Double): Double = 1.0 * weightKg * distanceM / 1000.0

    /** General exercise / gym workout: MET 5.0 (moderate effort, Compendium of Physical Activities). */
    const val EXERCISE_MET = 5.0
    fun exerciseKcal(weightKg: Double, activeMs: Long): Double = EXERCISE_MET * weightKg * activeMs / 3_600_000.0

    /** Estimate for a finished session of any type. */
    fun sessionKcal(type: ActivityType, weightKg: Double, distanceM: Double, movingMs: Long, elapsedMs: Long): Double = when (type) {
        ActivityType.CYCLING -> cyclingKcal(weightKg, distanceM, movingMs)
        ActivityType.RUNNING, ActivityType.JOGGING, ActivityType.TREADMILL -> runningKcal(weightKg, distanceM)
        ActivityType.EXERCISE -> exerciseKcal(weightKg, elapsedMs)
    }
}
