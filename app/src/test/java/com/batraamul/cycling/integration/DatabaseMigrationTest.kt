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
 * Proves that a database created by app v1.0.0 (schema 1) upgrades to v1.1.0 (schema 2) with all
 * existing data intact. Room checks the migrated schema against the exported app/schemas/2.json.
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
}
