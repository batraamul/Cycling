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
    version = 3,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

        /**
         * v2 → v3 (app 1.1.0 → 1.2.0): activity types. Existing rides become CYCLING (they were all
         * cycling), an active session's type is copied from its ride, and the stored mode name
         * CYCLING becomes SESSION.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ride ADD COLUMN activityType TEXT NOT NULL DEFAULT 'CYCLING'")
                db.execSQL("ALTER TABLE ride ADD COLUMN steps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracker_state ADD COLUMN sessionType TEXT DEFAULT NULL")
                db.execSQL("UPDATE tracker_state SET sessionType = 'CYCLING', mode = 'SESSION' WHERE mode = 'CYCLING'")
            }
        }
    }
}
