package com.batraamul.cycling.data

import androidx.room.withTransaction
import com.batraamul.cycling.core.Clock
import com.batraamul.cycling.core.GpsCalibrationMath
import com.batraamul.cycling.tracking.Environment
import java.io.Reader
import java.io.Writer

/** What a backup file contains, shown to the user before restoring. */
data class BackupPreview(
    val createdAtMs: Long,
    val appVersion: String,
    val device: String,
    val days: Int,
    val firstDay: String?,
    val lastDay: String?,
    val steps: Long,
    val sessions: Int,
    val routePoints: Int,
    val calibrations: Int,
)

data class RestoreResult(
    val daysAdded: Int,
    val daysUpdated: Int,
    val sessionsAdded: Int,
    val sessionsAlreadyPresent: Int,
    val calibrationsAdded: Int,
)

/**
 * Creates and restores backups. It only deals with Reader/Writer — where the file lives (Google
 * Drive, phone storage) is decided by the Android layer — so the logic is testable on the JVM.
 *
 * Restore is a MERGE, never a wipe:
 *  - a day present in both keeps the HIGHER step count (the same steps are never added twice);
 *  - a session is identified by its start time; one that already exists is skipped;
 *  - calibrations are added if not already present; the GPS factor is recomputed from them;
 *  - profile values (height, weight, goal, theme) come from the backup; a step length you measured on
 *    this phone after the backup was made is kept.
 * So restoring the same file twice changes nothing, and nothing recorded on this phone is deleted.
 */
class BackupManager(private val db: AppDatabase, private val clock: Clock, private val env: Environment) {
    private val tracking = db.tracking()
    private val settingsDao = db.settings()
    private val calibration = db.calibration()

    /** False on a fresh install: used to avoid overwriting a good backup with an empty one. */
    suspend fun hasData(): Boolean = tracking.dayCount() > 0 || tracking.completedCount() > 0

    suspend fun export(out: Writer) {
        BackupCodec.write(
            out,
            BackupCodec.Header(clock.nowMs(), env.appVersion(), DATABASE_VERSION, env.deviceModel()),
            settingsDao.getOrDefault(),
            tracking.allDays(),
            tracking.allCompletedRides(),   // an activity still in progress is not backed up until it is finished
            { id -> tracking.points(id) },
            calibration.allStep(),
            calibration.allGps(),
        )
    }

    suspend fun preview(input: Reader): BackupPreview {
        var header: BackupCodec.Header? = null
        var days = 0; var steps = 0L; var first: String? = null; var last: String? = null
        var sessions = 0; var points = 0; var cals = 0
        BackupCodec.read(input, object : BackupCodec.Sink {
            override suspend fun header(h: BackupCodec.Header) { header = h }
            override suspend fun day(d: DailyActivity) {
                days++; steps += d.steps
                if (first == null || d.day < first!!) first = d.day
                if (last == null || d.day > last!!) last = d.day
            }
            override suspend fun session(r: Ride, route: List<RoutePoint>) { sessions++; points += route.size; steps += r.steps }
            override suspend fun stepCalibration(c: StepCalibrationRecord) { cals++ }
            override suspend fun gpsCalibration(c: GpsCalibrationRecord) { cals++ }
        })
        val h = header!!
        return BackupPreview(h.createdAtMs, h.appVersion, h.device, days, first, last, steps, sessions, points, cals)
    }

    /** Merges a backup into the database in ONE transaction: if anything fails, nothing is changed. */
    suspend fun restore(input: Reader): RestoreResult = db.withTransaction {
        var daysAdded = 0; var daysUpdated = 0; var added = 0; var present = 0; var cals = 0
        var backupSettings: SettingsEntity? = null
        BackupCodec.read(input, object : BackupCodec.Sink {
            override suspend fun settings(s: SettingsEntity) { backupSettings = s }
            override suspend fun day(d: DailyActivity) {
                val existing = tracking.day(d.day)
                when {
                    existing == null -> { tracking.saveDay(d); daysAdded++ }
                    d.steps > existing.steps -> { tracking.saveDay(d.copy(updatedAtMs = maxOf(d.updatedAtMs, existing.updatedAtMs))); daysUpdated++ }
                }
            }
            override suspend fun session(r: Ride, route: List<RoutePoint>) {
                if (tracking.countRidesStartingAt(r.startTimeMs) > 0) { present++; return }
                val id = tracking.insertRide(r.copy(id = 0, status = RideStatus.COMPLETED, activeSinceMs = null))
                route.chunked(500).forEach { chunk -> tracking.insertPoints(chunk.map { it.copy(id = 0, rideId = id) }) }
                added++
            }
            override suspend fun stepCalibration(c: StepCalibrationRecord) {
                if (calibration.countStep(c.timeMs, c.kind) == 0) { calibration.insertStep(c.copy(id = 0)); cals++ }
            }
            override suspend fun gpsCalibration(c: GpsCalibrationRecord) {
                if (calibration.countGps(c.timeMs) == 0) { calibration.insertGps(c.copy(id = 0, rideId = null)); cals++ }
            }
        })
        backupSettings?.let { b ->
            val merged = mergeSettings(settingsDao.getOrDefault(), b)
            val applied = calibration.appliedGps().map { it.factor }
            val factor = GpsCalibrationMath.combine(applied)?.first
            settingsDao.save(if (factor != null) merged.copy(gpsFactor = factor) else merged)
        }
        RestoreResult(daysAdded, daysUpdated, added, present, cals)
    }

    companion object {
        const val DATABASE_VERSION = 3

        /** Profile from the backup; each calibration value from whichever side measured it more recently. */
        fun mergeSettings(current: SettingsEntity, backup: SettingsEntity): SettingsEntity {
            val currentWalkMeasured = current.walkStepSource != StepLengthSource.HEIGHT_ESTIMATE
            val useBackupWalk = !currentWalkMeasured || (backup.walkStepCalibratedAtMs ?: 0) > (current.walkStepCalibratedAtMs ?: 0)
            val useBackupRun = current.runStepM == null || (backup.runStepCalibratedAtMs ?: 0) > (current.runStepCalibratedAtMs ?: 0)
            val useBackupGps = (backup.gpsCalibratedAtMs ?: 0) > (current.gpsCalibratedAtMs ?: 0)
            var m = current.copy(
                heightCm = backup.heightCm, weightKg = backup.weightKg, themeId = backup.themeId,
                dailyStepGoal = backup.dailyStepGoal, stepSource = backup.stepSource,
            )
            if (useBackupWalk) m = m.copy(walkStepM = backup.walkStepM, walkStepSource = backup.walkStepSource,
                walkStepCalibratedAtMs = backup.walkStepCalibratedAtMs, walkStepConfidence = backup.walkStepConfidence)
            if (useBackupRun && backup.runStepM != null) m = m.copy(runStepM = backup.runStepM, runStepCalibratedAtMs = backup.runStepCalibratedAtMs)
            if (useBackupGps) m = m.copy(gpsFactor = backup.gpsFactor, gpsCalibratedAtMs = backup.gpsCalibratedAtMs)
            return m
        }
    }
}
