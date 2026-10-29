package com.batraamul.cycling.data

import com.batraamul.cycling.core.ActivityType
import com.batraamul.cycling.core.Calories
import com.batraamul.cycling.core.Clock
import com.batraamul.cycling.core.Confidence
import com.batraamul.cycling.core.GpsCalibrationEvaluation
import com.batraamul.cycling.core.GpsCalibrationMath
import com.batraamul.cycling.core.GpsQuality
import com.batraamul.cycling.core.key
import com.batraamul.cycling.tracking.Environment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class DaySummary(
    val date: LocalDate,
    /** Ordinary all-day walking steps (DailyActivity). */
    val walkSteps: Long,
    val walkDistanceM: Double,
    val cycleDistanceM: Double,
    val cycleRawDistanceM: Double,
    val cycleElapsedMs: Long,
    /** Number of cycling rides. */
    val rides: Int,
    /** Running + jogging + treadmill. */
    val runDistanceM: Double = 0.0,
    val runSteps: Long = 0,
    val runElapsedMs: Long = 0,
    val runSessions: Int = 0,
    val exerciseMs: Long = 0,
    val exerciseSessions: Int = 0,
    val walkKcal: Double = 0.0,
    val sessionKcal: Double = 0.0,
) {
    /** All steps of the day: walking + steps taken in running/jogging/treadmill sessions (never overlapping). */
    val steps get() = walkSteps + runSteps
    val totalActiveDistanceM get() = walkDistanceM + cycleDistanceM + runDistanceM
    val totalKcal get() = walkKcal + sessionKcal
    val activities get() = rides + runSessions + exerciseSessions
}

data class PeriodSummary(val days: List<DaySummary>) {
    val steps = days.sumOf { it.steps }
    val walkDistanceM = days.sumOf { it.walkDistanceM }
    val cycleDistanceM = days.sumOf { it.cycleDistanceM }
    val cycleElapsedMs = days.sumOf { it.cycleElapsedMs }
    val rides = days.sumOf { it.rides }
    val runDistanceM = days.sumOf { it.runDistanceM }
    val runElapsedMs = days.sumOf { it.runElapsedMs }
    val runSessions = days.sumOf { it.runSessions }
    val exerciseMs = days.sumOf { it.exerciseMs }
    val exerciseSessions = days.sumOf { it.exerciseSessions }
    val kcal = days.sumOf { it.totalKcal }
}

data class AllTime(
    val steps: Long, val walkDistanceM: Double, val cycleDistanceM: Double, val rides: Int, val cycleElapsedMs: Long,
    val runDistanceM: Double = 0.0, val runSessions: Int = 0, val exerciseMs: Long = 0, val exerciseSessions: Int = 0,
)

data class GpsCalibrationStatus(
    val factor: Double,
    val calibratedAtMs: Long?,
    val appliedCount: Int,
    val spread: Double?,
    val latest: GpsCalibrationRecord?,
    val confidence: Confidence,
)

/** Everything the screens read or write, in one place. Screens never touch DAOs directly. */
class AppRepository(private val db: AppDatabase, private val clock: Clock, private val env: Environment) {
    private val tracking = db.tracking()
    private val settingsDao = db.settings()
    private val calibration = db.calibration()

    val settings: Flow<SettingsEntity> = settingsDao.flow().map { it ?: SettingsEntity() }

    suspend fun settingsNow(): SettingsEntity = settingsDao.getOrDefault()
    suspend fun updateSettings(change: (SettingsEntity) -> SettingsEntity) = settingsDao.save(change(settingsDao.getOrDefault()))

    /** Emits today's date and re-emits after midnight, so "today" screens roll over by themselves. */
    val todayDate: Flow<LocalDate> = flow { while (true) { emit(clock.today()); delay(30_000) } }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val today: Flow<DaySummary> = todayDate.flatMapLatest { d -> period(d, d).map { it.days.first() } }

    /** Daily summaries from [from] to [to] inclusive, aggregated in SQL (fast even with years of data). */
    fun period(from: LocalDate, to: LocalDate): Flow<PeriodSummary> =
        combine(tracking.daysFlow(from.key(), to.key()), tracking.rideTotalsByDayFlow(from.key(), to.key()), settings) { walks, rides, s ->
            val w = walks.associateBy { it.day }
            val r = rides.groupBy { it.day }
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
                val dw = w[date.key()]
                val byType = (r[date.key()] ?: emptyList()).groupBy { ActivityType.of(it.activityType) }
                val cyc = byType[ActivityType.CYCLING] ?: emptyList()
                val run = byType.filterKeys { it.isRunLike }.values.flatten()
                val ex = byType[ActivityType.EXERCISE] ?: emptyList()
                DaySummary(date, dw?.steps ?: 0, dw?.walkDistanceM ?: 0.0,
                    cycleDistanceM = cyc.sumOf { it.distanceM }, cycleRawDistanceM = cyc.sumOf { it.rawDistanceM },
                    cycleElapsedMs = cyc.sumOf { it.elapsedMs }, rides = cyc.sumOf { it.rides },
                    runDistanceM = run.sumOf { it.distanceM }, runSteps = run.sumOf { it.steps }, runElapsedMs = run.sumOf { it.elapsedMs },
                    runSessions = run.sumOf { it.rides }, exerciseMs = ex.sumOf { it.elapsedMs }, exerciseSessions = ex.sumOf { it.rides },
                    walkKcal = Calories.walkingKcal(s.weightKg, dw?.walkDistanceM ?: 0.0),
                    sessionKcal = byType.values.flatten().sumOf { it.kcal })
            }.toList()
            PeriodSummary(days)
        }

    val allTime: Flow<AllTime> = combine(tracking.stepTotalsFlow(), tracking.rideTotalsFlow()) { s, totals ->
        val by = totals.groupBy { ActivityType.of(it.activityType) }
        val cyc = by[ActivityType.CYCLING] ?: emptyList()
        val run = by.filterKeys { it.isRunLike }.values.flatten()
        val ex = by[ActivityType.EXERCISE] ?: emptyList()
        AllTime((s.steps ?: 0) + run.sumOf { it.steps ?: 0 }, s.walkDistanceM ?: 0.0, cyc.sumOf { it.distanceM ?: 0.0 },
            cyc.sumOf { it.rides }, cyc.sumOf { it.elapsedMs ?: 0 },
            run.sumOf { it.distanceM ?: 0.0 }, run.sumOf { it.rides }, ex.sumOf { it.elapsedMs ?: 0 }, ex.sumOf { it.rides })
    }

    val completedRideCount: Flow<Int> = tracking.completedCountFlow()
    suspend fun ridesPage(offset: Int, limit: Int = 30) = tracking.ridesPage(limit, offset)
    fun rideFlow(id: Long) = tracking.rideFlow(id)
    suspend fun ride(id: Long) = tracking.ride(id)
    suspend fun routePoints(id: Long) = tracking.points(id)

    // ---------------- walking step calibration ----------------

    val stepCalibrationHistory = calibration.stepHistory()

    /** Saves a user-measured step length. From now on it is used as-is (never replaced by the height formula). */
    suspend fun saveStepLength(kind: String, method: String, inputs: String, resultM: Double, confidence: Confidence) {
        val now = clock.nowMs()
        calibration.insertStep(StepCalibrationRecord(timeMs = now, kind = kind, method = method, inputs = inputs, resultM = resultM, confidence = confidence.name))
        updateSettings {
            if (kind == KIND_RUN) it.copy(runStepM = resultM, runStepCalibratedAtMs = now)
            else it.copy(walkStepM = resultM, walkStepSource = method, walkStepCalibratedAtMs = now, walkStepConfidence = confidence.name)
        }
    }

    suspend fun clearRunStepLength() = updateSettings { it.copy(runStepM = null, runStepCalibratedAtMs = null) }

    // ---------------- GPS distance calibration ----------------

    val gpsCalibrationHistory = calibration.gpsHistory()

    val gpsStatus: Flow<GpsCalibrationStatus> = combine(settings, calibration.gpsHistory()) { s, history ->
        val applied = history.filter { it.applied && it.confirmed }
        val spread = GpsCalibrationMath.combine(applied.map { it.factor })?.second
        val latestApplied = applied.maxByOrNull { it.timeMs }
        GpsCalibrationStatus(s.gpsFactor, s.gpsCalibratedAtMs, applied.size, spread, history.firstOrNull(),
            GpsCalibrationMath.confidence(latestApplied?.let { runCatching { GpsQuality.valueOf(it.quality) }.getOrNull() }, applied.size, spread))
    }

    suspend fun evaluateCalibrationRide(rideId: Long, referenceKm: Double): Pair<Ride, GpsCalibrationEvaluation>? {
        val r = tracking.ride(rideId) ?: return null
        return r to GpsCalibrationMath.evaluate(r.rawDistanceM / 1000.0, referenceKm, r.meanAccuracyM ?: Double.NaN, r.fixCount)
    }

    /**
     * Records a calibration. The factor is applied ONLY when [apply] is true (explicit user
     * confirmation) and the quality was not rejected. The active factor is the mean of all applied
     * calibrations, so repeating the procedure refines it.
     */
    suspend fun saveGpsCalibration(rideId: Long, e: GpsCalibrationEvaluation, apply: Boolean): Boolean {
        val canApply = apply && e.quality != GpsQuality.REJECTED
        calibration.insertGps(GpsCalibrationRecord(timeMs = clock.nowMs(), rideId = rideId, rawKm = e.rawKm, referenceKm = e.referenceKm,
            factor = e.factor, meanAccuracyM = tracking.ride(rideId)?.meanAccuracyM, fixCount = tracking.ride(rideId)?.fixCount ?: 0,
            quality = e.quality.name, reasons = e.reasons.joinToString(" | "), deviceModel = env.deviceModel(), appVersion = env.appVersion(),
            confirmed = canApply, applied = canApply))
        if (canApply) {
            val factor = GpsCalibrationMath.combine(calibration.appliedGps().map { it.factor })!!.first
            updateSettings { it.copy(gpsFactor = factor, gpsCalibratedAtMs = clock.nowMs()) }
        }
        return canApply
    }

    /** "Reset to default GPS calculation": factor back to 1.0. History is kept for reference. */
    suspend fun resetGpsCalibration() {
        calibration.unapplyAllGps()
        updateSettings { it.copy(gpsFactor = 1.0, gpsCalibratedAtMs = null) }
    }

    companion object {
        const val KIND_WALK = "WALK"
        const val KIND_RUN = "RUN"
    }
}
