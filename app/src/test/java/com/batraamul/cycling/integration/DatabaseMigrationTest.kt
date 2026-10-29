package com.batraamul.cycling.integration

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.batraamul.cycling.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that databases created by older app versions upgrade with all existing data intact:
 * v1.0.0 (schema 1) → v1.1.0 (schema 2) → v1.2.0 (schema 3). Room checks each migrated schema
 * against the exported app/schemas/N.json, so a mismatch fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsHistory() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO daily_activity (day, steps, walkDistanceM, updatedAtMs) VALUES ('2026-10-27', 8421, 5894.7, 1)")
            execSQL("INSERT INTO tracker_state (id, mode, walkingEnabled, activeRideId, ridePaused, rideInterrupted, counterLastRaw, counterBootCount, lastFlushMs) VALUES (1, 'WALKING', 1, NULL, 0, 0, 52000, 7, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT steps FROM daily_activity WHERE day = '2026-10-27'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(8421L, c.getLong(0))
        }
        db.query("SELECT counterLastRaw, stepsPausedUntilMs FROM tracker_state WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(52000L, c.getLong(0))      // step baseline preserved → no double counting after update
            assertTrue(c.isNull(1))                 // new column starts as "not paused"
        }
    }

    @Test
    fun migrate2To3TurnsExistingRidesIntoCyclingSessions() {
        helper.createDatabase("migration-test-3", 2).apply {
            execSQL("INSERT INTO ride (id, day, startTimeMs, endTimeMs, status, isCalibration, rawDistanceM, factor, elapsedMs, activeSinceMs, movingMs, topSpeedMps, meanAccuracyM, fixCount, lastFixMs, segment, kcal) " +
                "VALUES (1, '2026-10-20', 1000, 2000, 'COMPLETED', 0, 12345.0, 1.02, 3600000, NULL, 3500000, 9.0, 4.0, 3500, 2000, 0, 300.0)")
            execSQL("INSERT INTO ride (id, day, startTimeMs, endTimeMs, status, isCalibration, rawDistanceM, factor, elapsedMs, activeSinceMs, movingMs, topSpeedMps, meanAccuracyM, fixCount, lastFixMs, segment, kcal) " +
                "VALUES (2, '2026-10-28', 5000, NULL, 'ACTIVE', 0, 800.0, 1.0, 0, 5000, 100000, 6.0, 5.0, 120, 6000, 0, 0.0)")
            // A ride was in progress when the user updated the app.
            execSQL("INSERT INTO tracker_state (id, mode, walkingEnabled, activeRideId, ridePaused, rideInterrupted, counterLastRaw, counterBootCount, lastFlushMs, stepsPausedUntilMs) VALUES (1, 'CYCLING', 1, 2, 0, 0, NULL, NULL, 1, NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-3", 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT activityType, steps, rawDistanceM FROM ride ORDER BY id").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("CYCLING", c.getString(0)); assertEquals(0L, c.getLong(1)); assertEquals(12345.0, c.getDouble(2), 1e-9)
            assertTrue(c.moveToNext()); assertEquals("CYCLING", c.getString(0))
        }
        db.query("SELECT mode, sessionType, activeRideId FROM tracker_state WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("SESSION", c.getString(0))   // the in-progress ride continues after the update
            assertEquals("CYCLING", c.getString(1))
            assertEquals(2L, c.getLong(2))
        }
    }

    @Test
    fun migrateAllTheWayFromVersion1() {
        helper.createDatabase("migration-test-chain", 1).apply {
            execSQL("INSERT INTO daily_activity (day, steps, walkDistanceM, updatedAtMs) VALUES ('2026-10-01', 4000, 2800.0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-chain", 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        db.query("SELECT steps FROM daily_activity").use { c -> assertTrue(c.moveToFirst()); assertEquals(4000L, c.getLong(0)) }
    }
}
