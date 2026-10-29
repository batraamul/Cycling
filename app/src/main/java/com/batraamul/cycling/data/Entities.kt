package com.batraamul.cycling.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Database tables. Each @Entity class = one table; each property = one column.
 *
 * The data model deliberately keeps these concepts separate:
 *   - ordinary daily steps & walking distance ........ DailyActivity
 *   - recorded activity sessions (per session) ........ Ride (+ RoutePoint for the route)
 *       "Ride" is the historical name: since v1.2.0 a row can be cycling, running, jogging,
 *       treadmill or exercise ([Ride.activityType]). Their steps are stored on the session,
 *       never in DailyActivity, so nothing is counted twice.
 *   - total daily activity ............................ computed by queries combining both
 */

/** One row per calendar day of ordinary walking. */
@Entity(tableName = "daily_activity")
data class DailyActivity(
    @PrimaryKey val day: String,          // "2026-10-28"
    val steps: Long = 0,
    /** Walking distance, computed with the step length that was active when the steps were counted. */
    val walkDistanceM: Double = 0.0,
    val updatedAtMs: Long = 0,
)

object RideStatus {
    const val ACTIVE = "ACTIVE"
    const val COMPLETED = "COMPLETED"
}

@Entity(tableName = "ride", indices = [Index("day"), Index("status")])
data class Ride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val status: String = RideStatus.ACTIVE,
    val isCalibration: Boolean = false,
    /** Distance from the GPS filter, before any calibration factor. Always kept. */
    val rawDistanceM: Double = 0.0,
    /** GPS calibration factor in force when the ride started (1.0 = none). Corrected = raw × factor. */
    val factor: Double = 1.0,
    /** Elapsed time excluding pauses, accumulated at each pause/finish. */
    val elapsedMs: Long = 0,
    /** Start of the current un-paused stretch, or null while paused/finished. */
    val activeSinceMs: Long? = null,
    val movingMs: Long = 0,
    val topSpeedMps: Double = 0.0,
    val meanAccuracyM: Double? = null,
    val fixCount: Int = 0,
    val lastFixMs: Long? = null,
    val segment: Int = 0,
    val kcal: Double = 0.0,
    /** Added in schema v3 (app v1.2.0): kind of session; older rows are CYCLING. */
    @ColumnInfo(defaultValue = "CYCLING") val activityType: String = "CYCLING",
    /** Added in schema v3: steps taken during a running/jogging/treadmill session. */
    @ColumnInfo(defaultValue = "0") val steps: Long = 0,
) {
    val correctedDistanceM: Double get() = rawDistanceM * factor
    val type: com.batraamul.cycling.core.ActivityType get() = com.batraamul.cycling.core.ActivityType.of(activityType)
}

@Entity(
    tableName = "route_point",
    foreignKeys = [ForeignKey(entity = Ride::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId")],
)
data class RoutePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    /** Increments after pause/interruption so the map never draws a line across a gap. */
    val segment: Int,
)

/** Single-row table (id = 1): the persisted Tracking Mode Controller state. */
@Entity(tableName = "tracker_state")
data class TrackerStateEntity(
    @PrimaryKey val id: Int = 1,
    val mode: String = "IDLE",
    val walkingEnabled: Boolean = true,
    val activeRideId: Long? = null,
    val ridePaused: Boolean = false,
    val rideInterrupted: Boolean = false,
    /** Hardware step-counter baseline. Saved in the same transaction as the steps it produced. */
    val counterLastRaw: Long? = null,
    val counterBootCount: Int? = null,
    val lastFlushMs: Long = 0,
    /** Added in schema v2 (app v1.1.0): step counting paused until this time (Long.MAX_VALUE = until resumed). */
    val stepsPausedUntilMs: Long? = null,
    /** Added in schema v3 (app v1.2.0): ActivityType of the active session, null if none. */
    val sessionType: String? = null,
)

object StepSourceSetting {
    const val AUTO = "AUTO"
    const val ACCELEROMETER = "ACCELEROMETER"
}

object StepLengthSource {
    const val HEIGHT_ESTIMATE = "HEIGHT_ESTIMATE"
    const val SINGLE_STEP = "SINGLE_STEP"
    const val COURSE = "COURSE"
    const val MANUAL = "MANUAL"
}

/** Single-row table (id = 1): user profile and preferences. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val onboardingDone: Boolean = false,
    val heightCm: Int = 170,
    val weightKg: Double = 70.0,
    val themeId: Int = 0,
    val dailyStepGoal: Int = 8000,
    val walkStepM: Double = 0.705,
    val walkStepSource: String = StepLengthSource.HEIGHT_ESTIMATE,
    val walkStepCalibratedAtMs: Long? = null,
    val walkStepConfidence: String? = null,
    val runStepM: Double? = null,
    val runStepCalibratedAtMs: Long? = null,
    val gpsFactor: Double = 1.0,
    val gpsCalibratedAtMs: Long? = null,
    val stepSource: String = StepSourceSetting.AUTO,
)

@Entity(tableName = "step_calibration")
data class StepCalibrationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val kind: String,              // WALK or RUN
    val method: String,            // SINGLE_STEP / COURSE / MANUAL
    val inputs: String,            // human-readable record of what was measured
    val resultM: Double,
    val confidence: String,
)

@Entity(tableName = "gps_calibration")
data class GpsCalibrationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val rideId: Long?,
    val rawKm: Double,
    val referenceKm: Double,
    val factor: Double,
    val meanAccuracyM: Double?,
    val fixCount: Int,
    val quality: String,
    val reasons: String,
    val deviceModel: String,
    val appVersion: String,
    /** User explicitly confirmed this calibration. */
    val confirmed: Boolean,
    /** Contributes to the active factor (false after "Reset to default"). */
    val applied: Boolean,
)
