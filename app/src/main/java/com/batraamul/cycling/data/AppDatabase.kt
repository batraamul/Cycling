package com.batraamul.cycling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local SQLite database (via Room). All data stays on the phone.
 * When the schema changes, bump [version] and add a Migration — never silently wipe data.
 */
@Database(
    entities = [
        DailyActivity::class, Ride::class, RoutePoint::class, TrackerStateEntity::class,
        SettingsEntity::class, StepCalibrationRecord::class, GpsCalibrationRecord::class,
    ],
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * v1 → v2 (app 1.0.0 → 1.1.0): adds the "steps paused until" column.
         * A migration upgrades the existing database in place, so updating the app keeps all history.
         * (Without it Room would refuse to open the old database and the app would crash.)
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracker_state ADD COLUMN stepsPausedUntilMs INTEGER DEFAULT NULL")
            }
        }
    }
}
