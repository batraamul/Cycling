package com.batraamul.cycling.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Aggregated session numbers for one day and one activity type. */
data class DayRideTotals(
    val day: String,
    val activityType: String,
    val distanceM: Double,
    val rawDistanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val rides: Int,
    val kcal: Double,
    val steps: Long,
)

data class Totals(val activityType: String, val distanceM: Double?, val elapsedMs: Long?, val rides: Int, val kcal: Double?, val steps: Long?)
data class StepTotals(val steps: Long?, val walkDistanceM: Double?, val days: Int)

@Dao
abstract class TrackingDao {
    // ---------- tracker state ----------
    @Query("SELECT * FROM tracker_state WHERE id = 1")
    abstract suspend fun state(): TrackerStateEntity?

    @Query("SELECT * FROM tracker_state WHERE id = 1")
    abstract fun stateFlow(): Flow<TrackerStateEntity?>

    @Upsert
    abstract suspend fun saveState(s: TrackerStateEntity)

    // ---------- daily walking ----------
    @Query("SELECT * FROM daily_activity WHERE day = :day")
    abstract suspend fun day(day: String): DailyActivity?

    @Query("SELECT * FROM daily_activity WHERE day = :day")
    abstract fun dayFlow(day: String): Flow<DailyActivity?>

    @Upsert
    abstract suspend fun saveDay(d: DailyActivity)

    @Query("SELECT * FROM daily_activity WHERE day BETWEEN :from AND :to ORDER BY day")
    abstract fun daysFlow(from: String, to: String): Flow<List<DailyActivity>>

    @Query("SELECT SUM(steps) AS steps, SUM(walkDistanceM) AS walkDistanceM, COUNT(*) AS days FROM daily_activity")
    abstract fun stepTotalsFlow(): Flow<StepTotals>

    /**
     * THE duplicate-prevention transaction: the new steps AND the step-counter baseline that
     * produced them are written together. Either both are saved or neither is.
     */
    @Transaction
    open suspend fun commitSteps(day: String, addSteps: Long, addDistanceM: Double, newState: TrackerStateEntity, nowMs: Long) {
        if (addSteps > 0) {
            val d = day(day) ?: DailyActivity(day)
            saveDay(d.copy(steps = d.steps + addSteps, walkDistanceM = d.walkDistanceM + addDistanceM, updatedAtMs = nowMs))
        }
        saveState(newState)
    }

    // ---------- rides ----------
    @Insert
    abstract suspend fun insertRide(r: Ride): Long

    @Upsert
    abstract suspend fun saveRide(r: Ride)

    @Query("SELECT * FROM ride WHERE id = :id")
    abstract suspend fun ride(id: Long): Ride?

    @Query("SELECT * FROM ride WHERE id = :id")
    abstract fun rideFlow(id: Long): Flow<Ride?>

    @Query("SELECT * FROM ride WHERE status = 'ACTIVE'")
    abstract suspend fun activeRides(): List<Ride>

    @Query("DELETE FROM ride WHERE id = :id")
    abstract suspend fun deleteRide(id: Long)

    @Insert
    abstract suspend fun insertPoints(points: List<RoutePoint>)

    @Query("SELECT * FROM route_point WHERE rideId = :rideId ORDER BY timeMs")
    abstract suspend fun points(rideId: Long): List<RoutePoint>

    @Query("SELECT COUNT(*) FROM route_point WHERE rideId = :rideId")
    abstract suspend fun pointCount(rideId: Long): Int

    /** Saves a batch of route points together with the ride's running totals. */
    @Transaction
    open suspend fun commitRideProgress(ride: Ride, points: List<RoutePoint>) {
        if (points.isNotEmpty()) insertPoints(points)
        saveRide(ride)
    }

    /** Begins a ride and switches the persisted mode in ONE transaction (no half-started rides). */
    @Transaction
    open suspend fun beginRide(ride: Ride, stateFor: (Long) -> TrackerStateEntity): Long {
        val id = insertRide(ride)
        saveState(stateFor(id))
        return id
    }

    /** Session progress + tracker state (incl. step-counter baseline) in ONE transaction. */
    @Transaction
    open suspend fun commitSession(ride: Ride, points: List<RoutePoint>, state: TrackerStateEntity) {
        if (points.isNotEmpty()) insertPoints(points)
        saveRide(ride)
        saveState(state)
    }

    @Transaction
    open suspend fun endRide(ride: Ride?, deleteRideId: Long?, newState: TrackerStateEntity) {
        if (ride != null) saveRide(ride)
        if (deleteRideId != null) deleteRide(deleteRideId)   // route points go too (CASCADE)
        saveState(newState)
    }

    // ---------- history & analytics (aggregated in SQL so years of data stay fast) ----------
    @Query("SELECT * FROM ride WHERE status = 'COMPLETED' ORDER BY startTimeMs DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun ridesPage(limit: Int, offset: Int): List<Ride>

    @Query("SELECT COUNT(*) FROM ride WHERE status = 'COMPLETED'")
    abstract fun completedCountFlow(): Flow<Int>

    @Query(
        """SELECT day, activityType, SUM(rawDistanceM * factor) AS distanceM, SUM(rawDistanceM) AS rawDistanceM,
           SUM(elapsedMs) AS elapsedMs, SUM(movingMs) AS movingMs, COUNT(*) AS rides, SUM(kcal) AS kcal, SUM(steps) AS steps
           FROM ride WHERE status = 'COMPLETED' AND day BETWEEN :from AND :to GROUP BY day, activityType ORDER BY day"""
    )
    abstract fun rideTotalsByDayFlow(from: String, to: String): Flow<List<DayRideTotals>>

    @Query(
        """SELECT activityType, SUM(rawDistanceM * factor) AS distanceM, SUM(elapsedMs) AS elapsedMs, COUNT(*) AS rides,
           SUM(kcal) AS kcal, SUM(steps) AS steps FROM ride WHERE status = 'COMPLETED' GROUP BY activityType"""
    )
    abstract fun rideTotalsFlow(): Flow<List<Totals>>

    // ---------- backup / restore ----------
    @Query("SELECT * FROM daily_activity ORDER BY day")
    abstract suspend fun allDays(): List<DailyActivity>

    @Query("SELECT * FROM ride WHERE status = 'COMPLETED' ORDER BY startTimeMs")
    abstract suspend fun allCompletedRides(): List<Ride>

    @Query("SELECT startTimeMs FROM ride")
    abstract suspend fun allRideStartTimes(): List<Long>

    @Query("SELECT COUNT(*) FROM daily_activity")
    abstract suspend fun dayCount(): Int

    @Query("SELECT COUNT(*) FROM ride WHERE status = 'COMPLETED'")
    abstract suspend fun completedCount(): Int

    @Query("SELECT COUNT(*) FROM ride WHERE startTimeMs = :startTimeMs")
    abstract suspend fun countRidesStartingAt(startTimeMs: Long): Int
}

@Dao
abstract class SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    abstract suspend fun get(): SettingsEntity?

    @Query("SELECT * FROM settings WHERE id = 1")
    abstract fun flow(): Flow<SettingsEntity?>

    @Upsert
    abstract suspend fun save(s: SettingsEntity)

    suspend fun getOrDefault(): SettingsEntity = get() ?: SettingsEntity()
}

@Dao
abstract class CalibrationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStep(r: StepCalibrationRecord): Long

    @Query("SELECT * FROM step_calibration ORDER BY timeMs DESC LIMIT 20")
    abstract fun stepHistory(): Flow<List<StepCalibrationRecord>>

    @Insert
    abstract suspend fun insertGps(r: GpsCalibrationRecord): Long

    @Query("SELECT * FROM gps_calibration ORDER BY timeMs DESC LIMIT 20")
    abstract fun gpsHistory(): Flow<List<GpsCalibrationRecord>>

    @Query("SELECT * FROM gps_calibration WHERE applied = 1 AND confirmed = 1 ORDER BY timeMs")
    abstract suspend fun appliedGps(): List<GpsCalibrationRecord>

    @Query("UPDATE gps_calibration SET applied = 0")
    abstract suspend fun unapplyAllGps()

    @Query("SELECT * FROM step_calibration ORDER BY timeMs")
    abstract suspend fun allStep(): List<StepCalibrationRecord>

    @Query("SELECT COUNT(*) FROM step_calibration WHERE timeMs = :timeMs AND kind = :kind")
    abstract suspend fun countStep(timeMs: Long, kind: String): Int

    @Query("SELECT COUNT(*) FROM gps_calibration WHERE timeMs = :timeMs")
    abstract suspend fun countGps(timeMs: Long): Int

    @Query("SELECT * FROM gps_calibration ORDER BY timeMs")
    abstract suspend fun allGps(): List<GpsCalibrationRecord>
}
