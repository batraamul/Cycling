package com.batraamul.cycling.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batraamul.cycling.core.Clock
import com.batraamul.cycling.core.GpsFix
import com.batraamul.cycling.core.GpsQuality
import com.batraamul.cycling.core.PAUSED_INDEFINITELY
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.data.AppDatabase
import com.batraamul.cycling.data.AppRepository
import com.batraamul.cycling.data.RideStatus
import com.batraamul.cycling.data.SettingsEntity
import com.batraamul.cycling.tracking.CommandResult
import com.batraamul.cycling.tracking.Environment
import com.batraamul.cycling.tracking.LocationFeed
import com.batraamul.cycling.tracking.StepListener
import com.batraamul.cycling.tracking.StepSensor
import com.batraamul.cycling.tracking.StepSourceKind
import com.batraamul.cycling.tracking.TrackingEngine
import com.batraamul.cycling.tracking.TrackingIssue
import com.batraamul.cycling.tracking.modeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * End-to-end tests of the Tracking Mode Controller with a REAL Room database and FAKE sensors.
 * These mirror the mandatory acceptance workflow (steps 5–15) as far as it can be reproduced
 * without a physical phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingEngineIntegrationTest {

    // ---------------- fakes ----------------
    class FakeClock(var now: Long) : Clock {
        override fun nowMs() = now
        override fun zone(): ZoneId = ZoneOffset.UTC
    }

    class FakeStepSensor(var kind: StepSourceKind = StepSourceKind.HARDWARE_COUNTER) : StepSensor {
        var active = false; var starts = 0
        override fun start(listener: StepListener): StepSourceKind { active = kind != StepSourceKind.NONE; starts++; return kind }
        override fun stop() { active = false }
    }

    class FakeLocation(var result: LocationFeed.StartResult = LocationFeed.StartResult.OK) : LocationFeed {
        var active = false
        override fun start(onFix: (GpsFix) -> Unit): LocationFeed.StartResult { active = result == LocationFeed.StartResult.OK; return result }
        override fun stop() { active = false }
    }

    class FakeEnv : Environment {
        var activity = true; var location = true; var boot: Int? = 7
        override fun hasActivityRecognition() = activity
        override fun hasLocationPermission() = location
        override fun bootCount() = boot
        override fun deviceModel() = "Test phone"
        override fun appVersion() = "test"
    }

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private val t0 = 1_793_000_000_000L          // 2026-10-26 ~ 08:53 UTC
    private val clock = FakeClock(t0)
    private val env = FakeEnv()
    private var steps = FakeStepSensor()
    private val gps = FakeLocation()
    private lateinit var engine: TrackingEngine
    private lateinit var repo: AppRepository
    private val mPerDegLat = 111_194.9

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        db.settings().save(SettingsEntity(onboardingDone = true, walkStepM = 0.70, weightKg = 70.0))
        engine = newEngine()
        repo = AppRepository(db, clock, env)
    }

    @After
    fun tearDown() { scope.cancel(); db.close() }

    private fun newEngine() = TrackingEngine(db, clock, { steps }, gps, env, scope, flushEverySteps = 1_000_000, flushEveryMs = Long.MAX_VALUE)

    private suspend fun todaySteps() = db.tracking().day(clock.today().toString())?.steps ?: 0L
    private suspend fun mode() = TrackingMode.valueOf(db.tracking().state()!!.mode)

    /** Rides north at 5 m/s for [seconds] seconds, one fix per second. */
    private suspend fun ride(e: TrackingEngine, seconds: Int, startNorthM: Double = 0.0) {
        for (i in 0..seconds) {
            clock.now += 1000
            e.injectFix(GpsFix(clock.now, 28.6 + (startNorthM + i * 5.0) / mPerDegLat, 77.2, 4f, 5f))
        }
    }

    // ---------------- tests ----------------

    @Test
    fun walkingIsOnByDefaultAndStepsPersist() = runBlocking {
        assertTrue(engine.restore(fromForeground = true))
        assertEquals(TrackingMode.WALKING, mode())
        assertTrue(steps.active)
        engine.injectCounter(10_000)      // baseline only
        engine.injectCounter(10_120)
        engine.flushNow()
        assertEquals(120L, todaySteps())
        assertEquals(84.0, db.tracking().day(clock.today().toString())!!.walkDistanceM, 1e-6) // 120 × 0.70 m
    }

    @Test
    fun cyclingSuspendsWalkingAndFinishResumesWithoutDoubleCounting() = runBlocking {
        engine.restore(true)
        engine.injectCounter(10_000); engine.injectCounter(10_100)            // 100 walking steps

        val start = engine.startCycling(false)
        assertTrue(start is CommandResult.Ok)
        assertEquals(100L, todaySteps())                                         // walking total saved first
        assertFalse("step detector must stop during the ride", steps.active)
        assertTrue(gps.active)
        assertEquals(TrackingMode.CYCLING, mode())

        engine.injectCounter(10_600)                                            // phone jiggling while pedalling
        ride(engine, 200)                                                       // ≈ 1 km
        assertEquals(100L, todaySteps())

        val fin = engine.finishRide() as CommandResult.Ok
        assertFalse(gps.active)
        assertTrue("walking resumes automatically", steps.active)
        assertEquals(TrackingMode.WALKING, mode())
        val r = db.tracking().ride(fin.rideId!!)!!
        assertEquals(RideStatus.COMPLETED, r.status)
        assertEquals(1000.0, r.rawDistanceM, 20.0)
        assertTrue(r.kcal > 0)

        engine.injectCounter(10_650)    // first reading after the ride: baseline only (ride-time counts excluded)
        engine.injectCounter(10_700)    // +50 real walking
        engine.flushNow()
        assertEquals(150L, todaySteps())
    }

    @Test
    fun processDeathDuringWalkingNeitherLosesNorDuplicatesSteps() = runBlocking {
        engine.restore(true)
        engine.injectCounter(5_000); engine.injectCounter(5_200); engine.flushNow()   // 200 saved
        engine.injectCounter(5_250)                                                  // 50 pending, then the process dies
        val revived = newEngine()
        revived.restore(false)
        revived.injectCounter(5_300)                                                 // delta from the SAVED baseline 5 200
        revived.flushNow()
        assertEquals(300L, todaySteps())
        revived.injectCounter(5_300); revived.flushNow()                              // replayed reading adds nothing
        assertEquals(300L, todaySteps())
    }

    @Test
    fun rebootMidRideMarksRideInterruptedAndNeverCountsItAsWalking() = runBlocking {
        engine.restore(true)
        engine.startCycling(false)
        ride(engine, 100)
        engine.flushNow()
        val rideId = db.tracking().state()!!.activeRideId!!

        gps.active = false
        env.boot = 8                                                                 // phone restarted
        val revived = newEngine()
        revived.restore(fromForeground = false)                                       // started by BootReceiver
        assertEquals(TrackingMode.CYCLING, mode())
        assertTrue(db.tracking().state()!!.rideInterrupted)
        assertTrue(TrackingIssue.RIDE_INTERRUPTED in revived.status.value.issues)
        assertFalse(gps.active)
        revived.injectCounter(300)
        assertEquals(0L, todaySteps())

        assertTrue(revived.recoverRide() is CommandResult.Ok)                          // user taps "Resume GPS"
        assertTrue(gps.active)
        ride(revived, 100, startNorthM = 2_000.0)                                     // gap is not bridged
        revived.finishRide()
        val r = db.tracking().ride(rideId)!!
        assertEquals(RideStatus.COMPLETED, r.status)
        assertEquals(1000.0, r.rawDistanceM, 25.0)
        assertEquals(1, db.tracking().ridesPage(10, 0).size)                          // no duplicate ride
    }

    @Test
    fun startCyclingWithGpsOffKeepsWalkingIntact() = runBlocking {
        engine.restore(true)
        engine.injectCounter(1_000); engine.injectCounter(1_040)
        gps.result = LocationFeed.StartResult.GPS_DISABLED
        val res = engine.startCycling(false)
        assertTrue(res is CommandResult.Failed)
        assertEquals(TrackingMode.WALKING, mode())
        assertTrue(steps.active)
        engine.injectCounter(1_100); engine.flushNow()
        assertEquals(100L, todaySteps())                                             // nothing lost or doubled
        assertNull(db.tracking().state()!!.activeRideId)
    }

    @Test
    fun pausingStopsTheClockAndDoesNotCountSteps() = runBlocking {
        engine.restore(true)
        engine.startCycling(false)
        ride(engine, 59)                         // 60 s riding
        engine.pauseRide()
        assertFalse(gps.active)
        assertFalse(steps.active)
        clock.now += 600_000                     // 10 min coffee break
        engine.injectCounter(9_999)
        engine.resumeRide()
        ride(engine, 59, startNorthM = 400.0)    // 60 s riding
        val id = (engine.finishRide() as CommandResult.Ok).rideId!!
        assertEquals(120_000.0, db.tracking().ride(id)!!.elapsedMs.toDouble(), 1_500.0)
        assertEquals(0L, todaySteps())
    }

    @Test
    fun discardDeletesOnlyThatRide() = runBlocking {
        engine.restore(true)
        engine.startCycling(false); ride(engine, 50); val keep = (engine.finishRide() as CommandResult.Ok).rideId!!
        engine.startCycling(false); ride(engine, 50); val drop = db.tracking().state()!!.activeRideId!!
        engine.flushNow()
        engine.discardRide()
        assertTrue(db.tracking().ride(keep) != null)
        assertNull(db.tracking().ride(drop))
        assertEquals(0, db.tracking().pointCount(drop))
        assertTrue(db.tracking().pointCount(keep) > 0)
        assertEquals(TrackingMode.WALKING, mode())
    }

    @Test
    fun stepsBeforeMidnightStayOnThatDay() = runBlocking {
        clock.now = java.time.LocalDate.of(2026, 10, 27).atTime(23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        engine.restore(true)
        engine.injectCounter(100); engine.injectCounter(180)            // 80 steps at 23:59, pending
        clock.now += 120_000                                           // 00:01 next day
        engine.injectCounter(200)                                      // +20 after midnight
        engine.flushNow()
        assertEquals(80L, db.tracking().day("2026-10-27")!!.steps)
        assertEquals(20L, db.tracking().day("2026-10-28")!!.steps)
    }

    @Test
    fun accelerometerFallbackCountsDetectedSteps() = runBlocking {
        steps = FakeStepSensor(StepSourceKind.ACCELEROMETER_WAKEUP)
        engine = newEngine()
        engine.restore(true)
        engine.injectSteps(4); engine.injectSteps(1); engine.flushNow()
        assertEquals(5L, todaySteps())
        assertEquals(StepSourceKind.ACCELEROMETER_WAKEUP, engine.status.value.stepSource)
    }

    @Test
    fun missingActivityPermissionIsReportedNotIgnored() = runBlocking {
        env.activity = false
        engine.restore(true)
        assertTrue(TrackingIssue.ACTIVITY_PERMISSION_MISSING in engine.status.value.issues)
        assertFalse(steps.active)
    }

    @Test
    fun gpsCalibrationIsAppliedOnlyWhenConfirmedAndCanBeReset() = runBlocking {
        engine.restore(true)
        engine.startCycling(isCalibration = true)
        ride(engine, 206)                                  // GPS measures ≈ 1.03 km on a "1.000 km" course
        val id = (engine.finishRide() as CommandResult.Ok).rideId!!
        val (_, eval) = repo.evaluateCalibrationRide(id, 1.0)!!
        assertEquals(GpsQuality.GOOD, eval.quality)

        repo.saveGpsCalibration(id, eval, apply = false)   // user declined
        assertEquals(1.0, repo.settingsNow().gpsFactor, 1e-12)

        repo.saveGpsCalibration(id, eval, apply = true)    // user confirmed
        val k = repo.settingsNow().gpsFactor
        assertEquals(eval.factor, k, 1e-9)

        engine.startCycling(false); ride(engine, 100)
        val r2 = db.tracking().ride((engine.finishRide() as CommandResult.Ok).rideId!!)!!
        assertEquals(k, r2.factor, 1e-12)                  // raw kept; corrected = raw × k
        assertEquals(r2.rawDistanceM * k, r2.correctedDistanceM, 1e-9)

        repo.resetGpsCalibration()
        assertEquals(1.0, repo.settingsNow().gpsFactor, 1e-12)
        assertEquals(0, repo.gpsStatus.first().appliedCount)
    }

    @Test
    fun weeklyAnalyticsSeparateWalkingAndCycling() = runBlocking {
        engine.restore(true)
        engine.injectCounter(0); engine.injectCounter(3_000)
        engine.startCycling(false); ride(engine, 200); engine.finishRide()
        val today = clock.today()
        val week = repo.period(today.minusDays(6), today).first()
        assertEquals(7, week.days.size)
        assertEquals(3_000L, week.steps)
        assertEquals(1, week.rides)
        assertEquals(1000.0, week.cycleDistanceM, 20.0)
        assertEquals(2100.0, week.walkDistanceM, 1e-6)
    }

    // ---------------- step pause (motorbike / car) ----------------

    @Test
    fun timedStepPauseExcludesVehicleStepsAndAutoResumes() = runBlocking {
        engine.restore(true)
        engine.injectCounter(1_000); engine.injectCounter(1_100)              // 100 real steps
        assertTrue(engine.pauseSteps(clock.now + 60 * 60_000L) is CommandResult.Ok)
        assertEquals(100L, todaySteps())                                     // saved before pausing
        assertFalse(steps.active)
        assertEquals(TrackingMode.WALKING, mode())
        engine.injectCounter(1_900)                                         // motorbike vibration "steps"
        clock.now += 61 * 60_000L                                            // pause expires
        engine.tickNow()
        assertTrue("auto-resumed", steps.active)
        engine.injectCounter(1_950)                                         // first reading: baseline only
        engine.injectCounter(2_000)                                         // +50 real walking
        engine.flushNow()
        assertEquals(150L, todaySteps())
    }

    @Test
    fun indefinitePauseSurvivesRestartUntilResumed() = runBlocking {
        engine.restore(true)
        engine.pauseSteps(PAUSED_INDEFINITELY)
        clock.now += 24 * 3_600_000L
        val revived = newEngine()
        revived.restore(fromForeground = false)                               // e.g. phone rebooted
        assertFalse(steps.active)
        assertTrue(revived.status.value.mode.stepsPaused)
        assertTrue(revived.resumeSteps() is CommandResult.Ok)
        assertTrue(steps.active)
        assertFalse(db.tracking().state()!!.modeState().stepsPaused)
    }

    @Test
    fun expiredPauseIsResumedOnRestore() = runBlocking {
        engine.restore(true)
        engine.pauseSteps(clock.now + 30 * 60_000L)
        clock.now += 45 * 60_000L
        val revived = newEngine()
        revived.restore(false)
        assertTrue(steps.active)
        assertFalse(revived.status.value.mode.stepsPaused)
    }

    @Test
    fun cannotPauseStepsDuringARide() = runBlocking {
        engine.restore(true)
        engine.startCycling(false)
        assertTrue(engine.pauseSteps(PAUSED_INDEFINITELY) is CommandResult.Failed)
    }
}
