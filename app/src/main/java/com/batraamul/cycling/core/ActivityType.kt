package com.batraamul.cycling.core

/**
 * The kinds of recorded activity session. Each one declares which sensors it uses — the engine
 * reads these flags instead of special-casing activity names, so adding a new kind is one line.
 *
 * | Type      | GPS (distance + route) | Step sensor        | Distance comes from          |
 * |-----------|------------------------|--------------------|------------------------------|
 * | CYCLING   | yes                    | no (pedalling ≠ steps) | GPS × cycling calibration |
 * | RUNNING   | yes                    | yes (steps, cadence) | GPS                        |
 * | JOGGING   | yes                    | yes                | GPS                          |
 * | TREADMILL | no (you stay in place) | yes                | steps × step length          |
 * | EXERCISE  | no                     | no                 | — (time and calories only)   |
 *
 * While any session is active, ordinary daily walking is paused, so the same movement is never
 * counted twice (the original walking/cycling rule, extended to every activity).
 */
enum class ActivityType(
    val label: String,
    val icon: String,
    val usesGps: Boolean,
    val countsSteps: Boolean,
) {
    CYCLING("Cycling", "🚴", usesGps = true, countsSteps = false),
    RUNNING("Running", "🏃", usesGps = true, countsSteps = true),
    JOGGING("Jogging", "👟", usesGps = true, countsSteps = true),
    TREADMILL("Treadmill", "🔁", usesGps = false, countsSteps = true),
    EXERCISE("Exercise", "💪", usesGps = false, countsSteps = false),
    ;

    /** Treadmill distance is estimated from steps × step length (no GPS movement indoors). */
    val distanceFromSteps: Boolean get() = !usesGps && countsSteps
    val hasDistance: Boolean get() = usesGps || countsSteps
    /** Running-type activities share a running-distance total in analytics. */
    val isRunLike: Boolean get() = this == RUNNING || this == JOGGING || this == TREADMILL

    companion object {
        /** Unknown or missing values (e.g. rides saved before v1.2.0) are cycling. */
        fun of(name: String?): ActivityType = entries.firstOrNull { it.name == name } ?: CYCLING
    }
}
