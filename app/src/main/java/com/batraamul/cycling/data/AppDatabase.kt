package com.batraamul.cycling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local SQLite database (via Room). All data stays on the phone.
 * When the schema changes, bump [version] and add a Migration — never silently wipe data.
 */
@Database(
    entities = [
        DailyActivity::class, Ride::class, RoutePoint::class, TrackerStateEntity::class,
        SettingsEntity::class, StepCalibrationRecord::class, GpsCalibrationRecord::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracking(): TrackingDao
    abstract fun settings(): SettingsDao
    abstract fun calibration(): CalibrationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cycling.db")
                // Write-ahead logging: readers (UI) never block the writer (tracking service).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
