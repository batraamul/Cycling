package com.batraamul.cycling.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batraamul.cycling.data.AppDatabase
import com.batraamul.cycling.data.BackupCodec
import com.batraamul.cycling.data.BackupManager
import com.batraamul.cycling.data.DailyActivity
import com.batraamul.cycling.data.GpsCalibrationRecord
import com.batraamul.cycling.data.Ride
import com.batraamul.cycling.data.RideStatus
import com.batraamul.cycling.data.RoutePoint
import com.batraamul.cycling.data.SettingsEntity
import com.batraamul.cycling.data.StepCalibrationRecord
import com.batraamul.cycling.data.StepLengthSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.io.StringWriter

/**
 * Backup → restore with REAL Room databases. The "old phone" and the "new phone" are two separate
 * in-memory databases; the backup file is a string in between (on the phone it is a file in Drive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreTest {
    private val clock = TrackingEngineIntegrationTest.FakeClock(1_793_000_000_000L)
    private val env = TrackingEngineIntegrationTest.FakeEnv()
    private lateinit var oldPhone: AppDatabase
    private lateinit var newPhone: AppDatabase

    private fun db(): AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @Before fun setUp() { oldPhone = db(); newPhone = db() }
    @After fun tearDown() { oldPhone.close(); newPhone.close() }

    private suspend fun fillOldPhone() {
        oldPhone.settings().save(SettingsEntity(onboardingDone = true, heightCm = 178, weightKg = 74.5, themeId = 9, dailyStepGoal = 10_000,
            walkStepM = 0.762, walkStepSource = StepLengthSource.SINGLE_STEP, walkStepCalibratedAtMs = 1000, walkStepConfidence = "HIGH",
            runStepM = 1.05, runStepCalibratedAtMs = 1100, gpsFactor = 1.031, gpsCalibratedAtMs = 1200))
        val t = oldPhone.tracking()
        t.saveDay(DailyActivity("2026-10-26", 8421, 6417.0, 5))
        t.saveDay(DailyActivity("2026-10-27", 12034, 9170.0, 6))
        val bike = t.insertRide(Ride(day = "2026-10-26", startTimeMs = 100_000, endTimeMs = 200_000, status = RideStatus.COMPLETED,
            rawDistanceM = 10_000.0, factor = 1.031, elapsedMs = 1_800_000, movingMs = 1_700_000, kcal = 410.0, fixCount = 3, meanAccuracyM = 4.2))
        t.insertPoints((0 until 3).map { RoutePoint(rideId = bike, timeMs = 100_000L + it * 1000, lat = 28.6 + it * 1e-4, lon = 77.2, accuracyM = 4f, speedMps = if (it == 1) null else 5f, segment = 0) })
        t.insertRide(Ride(day = "2026-10-27", startTimeMs = 300_000, endTimeMs = 400_000, status = RideStatus.COMPLETED,
            rawDistanceM = 3000.0, elapsedMs = 1_200_000, activityType = "TREADMILL", steps = 4100, kcal = 220.0))
        t.insertRide(Ride(day = "2026-10-27", startTimeMs = 500_000, status = RideStatus.ACTIVE, activityType = "RUNNING"))   // in progress: not backed up
        oldPhone.calibration().insertStep(StepCalibrationRecord(timeMs = 1000, kind = "WALK", method = "SINGLE_STEP", inputs = "76.2 cm ×3", resultM = 0.762, confidence = "HIGH"))
        oldPhone.calibration().insertGps(GpsCalibrationRecord(timeMs = 1200, rideId = bike, rawKm = 0.970, referenceKm = 1.0, factor = 1.031,
            meanAccuracyM = 3.9, fixCount = 210, quality = "GOOD", reasons = "", deviceModel = "OnePlus 11R", appVersion = "1.1.0", confirmed = true, applied = true))
    }

    private suspend fun backupOf(db: AppDatabase): String = StringWriter().also { BackupManager(db, clock, env).export(it) }.toString()

    @Test
    fun backupAndRestoreOntoAResetPhoneRecoversEverything() = runBlocking {
        fillOldPhone()
        val json = backupOf(oldPhone)
        assertTrue(json.contains("\"format\": \"cycling-app-backup\""))

        val mgr = BackupManager(newPhone, clock, env)
        assertFalse("fresh install has no data", mgr.hasData())
        val preview = mgr.preview(StringReader(json))
        assertEquals(2, preview.days); assertEquals(2, preview.sessions); assertEquals(3, preview.routePoints)
        assertEquals("2026-10-26", preview.firstDay); assertEquals("2026-10-27", preview.lastDay)

        val r = mgr.restore(StringReader(json))
        assertEquals(2, r.daysAdded); assertEquals(2, r.sessionsAdded); assertEquals(2, r.calibrationsAdded)

        val t = newPhone.tracking()
        assertEquals(8421L, t.day("2026-10-26")!!.steps)
        assertEquals(9170.0, t.day("2026-10-27")!!.walkDistanceM, 1e-9)
        val rides = t.allCompletedRides()
        assertEquals(listOf("CYCLING", "TREADMILL"), rides.map { it.activityType })
        val bike = rides[0]
        assertEquals(10_310.0, bike.correctedDistanceM, 1e-6)
        assertEquals(4.2, bike.meanAccuracyM!!, 1e-9)
        val pts = t.points(bike.id)
        assertEquals(3, pts.size); assertNull(pts[1].speedMps); assertEquals(28.6002, pts[2].lat, 1e-9)
        assertEquals(4100L, rides[1].steps)
        val s = newPhone.settings().getOrDefault()
        assertEquals(178, s.heightCm); assertEquals(74.5, s.weightKg, 0.0); assertEquals(9, s.themeId)
        assertEquals(0.762, s.walkStepM, 0.0); assertEquals(StepLengthSource.SINGLE_STEP, s.walkStepSource)
        assertEquals(1.05, s.runStepM!!, 0.0)
        assertEquals(1.031, s.gpsFactor, 1e-9)        // recomputed from the restored, applied calibration
        assertTrue(mgr.hasData())
    }

    @Test
    fun restoringTwiceChangesNothing() = runBlocking {
        fillOldPhone()
        val json = backupOf(oldPhone)
        val mgr = BackupManager(newPhone, clock, env)
        mgr.restore(StringReader(json))
        val again = mgr.restore(StringReader(json))
        assertEquals(0, again.daysAdded); assertEquals(0, again.daysUpdated); assertEquals(0, again.sessionsAdded)
        assertEquals(2, again.sessionsAlreadyPresent); assertEquals(0, again.calibrationsAdded)
        assertEquals(2, newPhone.tracking().allCompletedRides().size)
        assertEquals(8421L, newPhone.tracking().day("2026-10-26")!!.steps)   // not doubled
    }

    @Test
    fun mergeKeepsTheHigherStepCountAndDeletesNothing() = runBlocking {
        fillOldPhone()
        val json = backupOf(oldPhone)
        val t = newPhone.tracking()
        t.saveDay(DailyActivity("2026-10-26", 9000, 7000.0, 9))     // the new phone already counted more that day
        t.saveDay(DailyActivity("2026-10-27", 500, 380.0, 9))       // … and less on this one
        t.saveDay(DailyActivity("2026-10-29", 2500, 1900.0, 9))     // a day only the new phone has
        val own = t.insertRide(Ride(day = "2026-10-29", startTimeMs = 900_000, endTimeMs = 950_000, status = RideStatus.COMPLETED, activityType = "EXERCISE", elapsedMs = 50_000))

        val r = BackupManager(newPhone, clock, env).restore(StringReader(json))
        assertEquals(0, r.daysAdded); assertEquals(1, r.daysUpdated)
        assertEquals(9000L, t.day("2026-10-26")!!.steps)
        assertEquals(12034L, t.day("2026-10-27")!!.steps)
        assertEquals(2500L, t.day("2026-10-29")!!.steps)
        assertEquals("EXERCISE", t.ride(own)!!.activityType)
        assertEquals(3, t.allCompletedRides().size)
    }

    @Test
    fun aStepLengthMeasuredAfterTheBackupIsKept() {
        val phone = SettingsEntity(walkStepM = 0.80, walkStepSource = StepLengthSource.COURSE, walkStepCalibratedAtMs = 5000)
        val backup = SettingsEntity(heightCm = 181, walkStepM = 0.70, walkStepSource = StepLengthSource.MANUAL, walkStepCalibratedAtMs = 1000)
        val m = BackupManager.mergeSettings(phone, backup)
        assertEquals(0.80, m.walkStepM, 0.0)
        assertEquals(181, m.heightCm)
        // …but a height-formula estimate on the new phone is replaced by the measured value from the backup.
        assertEquals(0.70, BackupManager.mergeSettings(SettingsEntity(), backup).walkStepM, 0.0)
    }

    @Test
    fun aDamagedBackupChangesNothing() = runBlocking {
        fillOldPhone()
        val json = backupOf(oldPhone)
        val cut = json.substring(0, json.indexOf("TREADMILL"))           // download cut off in the middle of the sessions
        try { BackupManager(newPhone, clock, env).restore(StringReader(cut)); fail("expected FormatException") }
        catch (e: BackupCodec.FormatException) { assertTrue(e.message!!.contains("damaged")) }
        assertEquals("transaction rolled back", 0, newPhone.tracking().dayCount())
        assertEquals(0, newPhone.tracking().completedCount())
    }

    @Test
    fun otherFilesAndNewerFormatsAreRefused() = runBlocking {
        val mgr = BackupManager(newPhone, clock, env)
        for (bad in listOf("""{"name":"something else"}""", "[1,2,3]", "not json at all",
                """{"format":"cycling-app-backup","formatVersion":99,"dailyActivity":[]}""")) {
            try { mgr.preview(StringReader(bad)); fail("accepted: $bad") } catch (e: BackupCodec.FormatException) { }
        }
    }

    @Test
    fun unknownFieldsFromAFutureVersionAreIgnored() = runBlocking {
        val json = """{"format":"cycling-app-backup","formatVersion":1,"createdAtMs":1,"appVersion":"9.9","databaseVersion":7,"device":"x",
            "newThing":{"a":[1,2]},"dailyActivity":[{"day":"2026-01-01","steps":42,"walkDistanceM":30.0,"mood":"happy"}]}"""
        BackupManager(newPhone, clock, env).restore(StringReader(json))
        assertEquals(42L, newPhone.tracking().day("2026-01-01")!!.steps)
    }
}
