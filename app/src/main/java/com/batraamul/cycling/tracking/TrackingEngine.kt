package com.batraamul.cycling.tracking

import com.batraamul.cycling.core.Calories
import com.batraamul.cycling.core.Clock
import com.batraamul.cycling.core.CounterBaseline
import com.batraamul.cycling.core.CounterUpdate
import com.batraamul.cycling.core.DistanceAccumulator
import com.batraamul.cycling.core.GpsFix
import com.batraamul.cycling.core.ModeEvent
import com.batraamul.cycling.core.ModeState
import com.batraamul.cycling.core.StepCounterMath
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.core.TrackingStateMachine
import com.batraamul.cycling.core.Transition
import com.batraamul.cycling.core.key
import com.batraamul.cycling.data.AppDatabase
import com.batraamul.cycling.data.Ride
import com.batraamul.cycling.data.RideStatus
import com.batraamul.cycling.data.RoutePoint
import com.batraamul.cycling.data.StepSourceSetting
import com.batraamul.cycling.data.TrackerStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CommandResult {
    data class Ok(val rideId: Long? = null) : CommandResult
    data class Failed(val issue: TrackingIssue?, val message: String) : CommandResult
}

/**
 * The Tracking Mode Controller. ONE instance per process owns:
 *   - the current mode (persisted in the tracker_state table),
 *   - the step sensor (walking) and the GPS feed (cycling) — never both,
 *   - batching of database writes.
 *
 * Concurrency: every public operation and every sensor event runs under one [mutex] on a
 * single-threaded [scope], so two operations can never interleave (e.g. a late step event
 * cannot sneak in while "Start Cycling" is half-way through).
 *
 * This class has no dependency on Android's Service class, which is why it can be tested
 * on the JVM with fake sensors and a real (in-memory) database.
 */
class TrackingEngine(
    db: AppDatabase,
    private val clock: Clock,
    private val stepSensorFactory: (forceAccelerometer: Boolean) -> StepSensor,
    private val location: LocationFeed,
    private val env: Environment,
    private val scope: CoroutineScope,
    private val flushEverySteps: Int = 50,
    private val flushEveryMs: Long = 60_000,
    private val pointBatchSize: Int = 15,
    private val pointFlushMs: Long = 30_000,
    private val tickerMs: Long = 30_000,
) {
    var hooks: EngineHooks? = null

    private val dao = db.tracking()
    private val settingsDao = db.settings()
    private val mutex = Mutex()
    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status.asStateFlow()

    private var running = false
    private var state: TrackerStateEntity? = null
    private val issues = mutableSetOf<TrackingIssue>()
    private var ticker: Job? = null

    // ---- walking ----
    private var stepSensor: StepSensor? = null
    private var stepKind: StepSourceKind? = null
    private var memBaseline = CounterBaseline(null, null)
    private var pendingSteps = 0L
    private var pendingDistanceM = 0.0
    private var pendingDay: String? = null
    private var lastFlushMs = 0L
    private var lastStepEventMs = 0L

    // ---- cycling ----
    private var ride: Ride? = null
    private var accumulator = DistanceAccumulator()
    private val pendingPoints = mutableListOf<RoutePoint>()
    private val routePreview = ArrayList<GpsFix>()
    private var lastFix: GpsFix? = null
    private var lastPointFlushMs = 0L
    private var locationActive = false

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /**
     * Brings the engine in line with the saved state. Called when the service starts for any
     * reason: app opened, phone rebooted, process recreated by Android.
     * @param fromForeground true when the user is looking at the app (GPS may be restarted).
     * @return true if tracking should continue (service must stay in the foreground).
     */
    suspend fun restore(fromForeground: Boolean): Boolean = mutex.withLock {
        val settings = settingsDao.getOrDefault()
        if (!settings.onboardingDone) { publish(); return false }
        var s = dao.state() ?: TrackerStateEntity(mode = TrackingMode.WALKING.name, walkingEnabled = true).also { dao.saveState(it) }

        // Self-repair: a CYCLING state whose ride no longer exists falls back to walking.
        if (s.mode == TrackingMode.CYCLING.name && (s.activeRideId == null || dao.ride(s.activeRideId!!) == null)) {
            s = s.copy(mode = if (s.walkingEnabled) TrackingMode.WALKING.name else TrackingMode.IDLE.name,
                activeRideId = null, ridePaused = false, rideInterrupted = false, counterLastRaw = null)
            dao.saveState(s)
        }
        // Orphan ACTIVE rides (should not exist thanks to transactions) are closed, never duplicated.
        dao.activeRides().filter { it.id != s.activeRideId }.forEach {
            dao.saveRide(it.copy(status = RideStatus.COMPLETED, endTimeMs = it.lastFixMs ?: it.startTimeMs, activeSinceMs = null))
        }
        state = s
        running = true
        startTicker()

        when (s.mode()) {
            TrackingMode.WALKING -> if (stepSensor == null) startWalkingSensorsLocked() else Unit
            TrackingMode.CYCLING -> restoreRideLocked(fromForeground)
            TrackingMode.IDLE -> stopStepSensorLocked()
        }
        publish()
        state?.mode() != TrackingMode.IDLE
    }

    /** Flushes everything and releases sensors. */
    suspend fun shutdown() = mutex.withLock {
        flushStepsLocked(force = true)
        flushPointsLocked(force = true)
        stopStepSensorLocked()
        stopLocationLocked()
        ticker?.cancel(); ticker = null
        running = false
        publish()
    }

    // =====================================================================================
    // Mode transitions
    // =====================================================================================

    suspend fun startCycling(isCalibration: Boolean = false): CommandResult = mutex.withLock {
        val s = state ?: return CommandResult.Failed(null, "Tracking has not started yet.")
        val check = TrackingStateMachine.reduce(s.modeState(), ModeEvent.StartCycling(0))
        if (check is Transition.Rejected) return CommandResult.Failed(null, check.reason)
        if (!env.hasLocationPermission()) {
            issues += TrackingIssue.LOCATION_PERMISSION_MISSING; publish()
            return CommandResult.Failed(TrackingIssue.LOCATION_PERMISSION_MISSING, "Location permission is needed to record a ride.")
        }
        // 1-3: save the walking total, then stop the step detector BEFORE the ride starts.
        flushStepsLocked(force = true)
        val wasWalking = s.mode() == TrackingMode.WALKING
        stopStepSensorLocked()

        // 4: start GPS. If that fails, walking resumes exactly as before.
        val result = location.start(::onFixFromSensor)
        if (result != LocationFeed.StartResult.OK) {
            if (wasWalking) startWalkingSensorsLocked()
            val issue = if (result == LocationFeed.StartResult.NO_PERMISSION) TrackingIssue.LOCATION_PERMISSION_MISSING else TrackingIssue.GPS_DISABLED
            issues += issue; publish()
            return CommandResult.Failed(issue, when (result) {
                LocationFeed.StartResult.GPS_DISABLED -> "GPS is switched off. Turn on Location in quick settings."
                LocationFeed.StartResult.NO_GPS_HARDWARE -> "This phone has no GPS receiver."
                else -> "Location permission is needed to record a ride."
            })
        }
        locationActive = true
        issues -= TrackingIssue.LOCATION_PERMISSION_MISSING
        issues -= TrackingIssue.GPS_DISABLED

        // 5: create the ride and switch mode in ONE transaction. The step-counter baseline is
        // invalidated, so steps the phone registers during the ride are never added later.
        val now = clock.nowMs()
        val settings = settingsDao.getOrDefault()
        val newRide = Ride(day = clock.dayOf(now).key(), startTimeMs = now, isCalibration = isCalibration,
            factor = if (isCalibration) 1.0 else settings.gpsFactor, activeSinceMs = now)
        val id = dao.beginRide(newRide) { rideId ->
            s.copy(mode = TrackingMode.CYCLING.name, activeRideId = rideId, ridePaused = false, rideInterrupted = false,
                counterLastRaw = null, counterBootCount = null)
        }
        state = dao.state()
        ride = newRide.copy(id = id)
        accumulator = DistanceAccumulator()
        pendingPoints.clear(); routePreview.clear(); lastFix = null
        lastPointFlushMs = now
        publish()
        CommandResult.Ok(id)
    }

    suspend fun pauseRide(): CommandResult = mutex.withLock {
        val s = state ?: return CommandResult.Failed(null, "Not running.")
        val t = TrackingStateMachine.reduce(s.modeState(), ModeEvent.PauseRide)
        if (t is Transition.Rejected) return CommandResult.Failed(null, t.reason)
        val r = ride ?: return CommandResult.Failed(null, "No ride loaded.")
        stopLocationLocked()
        flushPointsLocked(force = true)
        val now = clock.nowMs()
        val updated = r.copy(elapsedMs = r.elapsedMs + (r.activeSinceMs?.let { now - it } ?: 0), activeSinceMs = null, segment = r.segment + 1)
        accumulator.breakSegment()
        commitRideAndState(updated, s.copy(ridePaused = true))
        CommandResult.Ok(r.id)
    }

    suspend fun resumeRide(): CommandResult = resumeOrRecover(ModeEvent.ResumeRide)

    /** After an interruption (e.g. reboot mid-ride): restart GPS on the user's request. */
    suspend fun recoverRide(): CommandResult = resumeOrRecover(ModeEvent.RideRecovered)

    private suspend fun resumeOrRecover(event: ModeEvent): CommandResult = mutex.withLock {
        val s = state ?: return CommandResult.Failed(null, "Not running.")
        val t = TrackingStateMachine.reduce(s.modeState(), event)
        if (t is Transition.Rejected) return CommandResult.Failed(null, t.reason)
        val r = ride ?: return CommandResult.Failed(null, "No ride loaded.")
        val res = if (env.hasLocationPermission()) location.start(::onFixFromSensor) else LocationFeed.StartResult.NO_PERMISSION
        if (res != LocationFeed.StartResult.OK) {
            return CommandResult.Failed(TrackingIssue.GPS_DISABLED, "Could not start GPS ($res). Check that Location is on.")
        }
        locationActive = true
        issues -= TrackingIssue.RIDE_INTERRUPTED; issues -= TrackingIssue.GPS_DISABLED
        accumulator.breakSegment()
        val now = clock.nowMs()
        val updated = r.copy(activeSinceMs = now, segment = if (event == ModeEvent.RideRecovered) r.segment + 1 else r.segment)
        commitRideAndState(updated, s.copy(ridePaused = false, rideInterrupted = false))
        CommandResult.Ok(r.id)
    }

    /** Finish Cycling: save the ride, then automatically resume walking tracking. */
    suspend fun finishRide(): CommandResult = mutex.withLock { endRideLocked(discard = false) }

    /** Discard: delete only this ride (and its route), then resume walking. */
    suspend fun discardRide(): CommandResult = mutex.withLock { endRideLocked(discard = true) }

    private suspend fun endRideLocked(discard: Boolean): CommandResult {
        val s = state ?: return CommandResult.Failed(null, "Not running.")
        val t = TrackingStateMachine.reduce(s.modeState(), if (discard) ModeEvent.DiscardRide else ModeEvent.FinishRide)
        if (t is Transition.Rejected) return CommandResult.Failed(null, t.reason)
        val next = (t as Transition.Ok).state
        val r = ride ?: dao.ride(s.activeRideId ?: -1) ?: return CommandResult.Failed(null, "No ride loaded.")
        stopLocationLocked()
        val newState = s.copy(mode = next.mode.name, activeRideId = null, ridePaused = false, rideInterrupted = false,
            counterLastRaw = null, counterBootCount = null)
        if (discard) {
            pendingPoints.clear()
            dao.endRide(ride = null, deleteRideId = r.id, newState = newState)
        } else {
            val now = clock.nowMs()
            val weight = settingsDao.getOrDefault().weightKg
            val elapsed = r.elapsedMs + (r.activeSinceMs?.let { now - it } ?: 0)
            val final = r.copy(status = RideStatus.COMPLETED, endTimeMs = now, elapsedMs = elapsed, activeSinceMs = null,
                kcal = Calories.cyclingKcal(weight, r.correctedDistanceM, r.movingMs))
            if (pendingPoints.isNotEmpty()) { dao.insertPoints(pendingPoints.toList()); pendingPoints.clear() }
            dao.endRide(ride = final, deleteRideId = null, newState = newState)
        }
        state = newState
        ride = null; routePreview.clear(); lastFix = null
        issues -= TrackingIssue.RIDE_INTERRUPTED
        if (next.mode == TrackingMode.WALKING) startWalkingSensorsLocked()
        publish()
        return CommandResult.Ok(r.id)
    }

    suspend fun setWalkingEnabled(enabled: Boolean): CommandResult = mutex.withLock {
        val s = state ?: dao.state() ?: TrackerStateEntity()
        val t = TrackingStateMachine.reduce(s.modeState(), if (enabled) ModeEvent.EnableWalking else ModeEvent.DisableWalking)
        val next = (t as Transition.Ok).state
        if (s.mode() == TrackingMode.WALKING && next.mode != TrackingMode.WALKING) {
            flushStepsLocked(force = true)
            stopStepSensorLocked()
        }
        // Invalidate the baseline so steps taken while tracking was off are not added later.
        val ns = s.copy(walkingEnabled = enabled, mode = next.mode.name,
            counterLastRaw = if (next.mode != s.mode()) null else s.counterLastRaw,
            counterBootCount = if (next.mode != s.mode()) null else s.counterBootCount)
        dao.saveState(ns); state = ns
        if (running && next.mode == TrackingMode.WALKING && stepSensor == null) startWalkingSensorsLocked()
        publish()
        CommandResult.Ok()
    }

    /** Re-reads settings that affect sensors (e.g. step-source override) and restarts the step sensor. */
    suspend fun restartStepSensor() = mutex.withLock {
        if (state?.mode() == TrackingMode.WALKING && running) {
            flushStepsLocked(force = true)
            stopStepSensorLocked()
            startWalkingSensorsLocked()
            publish()
        }
    }

    // =====================================================================================
    // Walking: step events
    // =====================================================================================

    private val stepListener = object : StepListener {
        override fun onCounter(raw: Long) { scope.launch { mutex.withLock { onCounterLocked(raw) } } }
        override fun onSteps(count: Int) { scope.launch { mutex.withLock { onDetectedStepsLocked(count) } } }
    }

    /** Test entry points (production events arrive through [stepListener]). */
    internal suspend fun injectCounter(raw: Long) = mutex.withLock { onCounterLocked(raw) }
    internal suspend fun injectSteps(n: Int) = mutex.withLock { onDetectedStepsLocked(n) }
    internal suspend fun injectFix(fix: GpsFix) = mutex.withLock { onFixLocked(fix) }
    internal suspend fun flushNow() = mutex.withLock { flushStepsLocked(true); flushPointsLocked(true) }

    private suspend fun onCounterLocked(raw: Long) {
        val s = state ?: return
        if (!s.modeState().countsSteps || stepSensor == null) return   // late event after a mode change: ignore
        rolloverDayLocked()
        val u = StepCounterMath.update(memBaseline, raw, env.bootCount())
        memBaseline = u.baseline
        if (u.newSteps > 0) addPendingLocked(u.newSteps)
        if (u.note != CounterUpdate.Note.NORMAL) flushStepsLocked(force = true) else maybeFlushLocked()
        publish()
    }

    private suspend fun onDetectedStepsLocked(n: Int) {
        val s = state ?: return
        if (!s.modeState().countsSteps || stepSensor == null || n <= 0) return
        rolloverDayLocked()
        addPendingLocked(n.toLong())
        maybeFlushLocked()
        publish()
    }

    private suspend fun addPendingLocked(n: Long) {
        val now = clock.nowMs()
        val settings = settingsDao.getOrDefault()
        // Cadence decides walking vs running step length (only if a running length was calibrated).
        val minutes = (now - lastStepEventMs) / 60_000.0
        val cadence = if (lastStepEventMs > 0 && minutes in 0.05..5.0) n / minutes else 0.0
        lastStepEventMs = now
        val stride = if (settings.runStepM != null && cadence >= RUN_CADENCE_SPM) settings.runStepM else settings.walkStepM
        if (pendingDay == null) pendingDay = clock.dayOf(now).key()
        pendingSteps += n
        pendingDistanceM += n * stride
    }

    private suspend fun rolloverDayLocked() {
        val today = clock.today().key()
        if (pendingDay != null && pendingDay != today) flushStepsLocked(force = true)
    }

    private suspend fun maybeFlushLocked() {
        val now = clock.nowMs()
        if (pendingSteps >= flushEverySteps || (pendingSteps > 0 && now - lastFlushMs >= flushEveryMs)) flushStepsLocked(force = true)
    }

    private suspend fun flushStepsLocked(force: Boolean) {
        val s = state ?: return
        if (!force && pendingSteps == 0L) return
        val baselineChanged = memBaseline.lastRaw != s.counterLastRaw || memBaseline.bootCount != s.counterBootCount
        if (pendingSteps == 0L && !baselineChanged) return
        val now = clock.nowMs()
        val ns = s.copy(counterLastRaw = memBaseline.lastRaw, counterBootCount = memBaseline.bootCount, lastFlushMs = now)
        dao.commitSteps(pendingDay ?: clock.dayOf(now).key(), pendingSteps, pendingDistanceM, ns, now)
        state = ns
        pendingSteps = 0; pendingDistanceM = 0.0; pendingDay = null
        lastFlushMs = now
    }

    private suspend fun startWalkingSensorsLocked() {
        if (!env.hasActivityRecognition()) {
            issues += TrackingIssue.ACTIVITY_PERMISSION_MISSING
            stepKind = StepSourceKind.NONE
            return
        }
        issues -= TrackingIssue.ACTIVITY_PERMISSION_MISSING
        val s = state ?: return
        memBaseline = CounterBaseline(s.counterLastRaw, s.counterBootCount)
        lastStepEventMs = 0
        lastFlushMs = clock.nowMs()
        val force = settingsDao.getOrDefault().stepSource == StepSourceSetting.ACCELEROMETER
        val sensor = stepSensorFactory(force)
        val kind = sensor.start(stepListener)
        stepKind = kind
        if (kind == StepSourceKind.NONE) {
            issues += TrackingIssue.NO_STEP_SENSOR
            sensor.stop()
        } else {
            issues -= TrackingIssue.NO_STEP_SENSOR
            stepSensor = sensor
        }
    }

    private fun stopStepSensorLocked() {
        stepSensor?.stop()
        stepSensor = null
        stepKind = null
    }

    // =====================================================================================
    // Cycling: GPS
    // =====================================================================================

    private fun onFixFromSensor(fix: GpsFix) { scope.launch { mutex.withLock { onFixLocked(fix) } } }

    private suspend fun onFixLocked(fix: GpsFix) {
        val s = state ?: return
        if (!s.modeState().recordsGps) return
        val r = ride ?: return
        lastFix = fix
        val res = accumulator.add(fix)
        if (res == DistanceAccumulator.Result.FIRST || res == DistanceAccumulator.Result.ACCEPTED) {
            pendingPoints += RoutePoint(rideId = r.id, timeMs = fix.timeMs, lat = fix.lat, lon = fix.lon,
                accuracyM = fix.accuracyM, speedMps = fix.speedMps, segment = r.segment)
            routePreview += fix
            if (routePreview.size > MAX_PREVIEW_POINTS) decimate(routePreview)
        }
        ride = r.copy(rawDistanceM = accumulator.rawDistanceM, movingMs = accumulator.movingMs,
            topSpeedMps = accumulator.topSpeedMps, fixCount = accumulator.acceptedFixes,
            meanAccuracyM = accumulator.meanAccuracyM.takeIf { !it.isNaN() }, lastFixMs = fix.timeMs)
        flushPointsLocked(force = false)
        publish()
    }

    private suspend fun flushPointsLocked(force: Boolean) {
        val r = ride ?: return
        val now = clock.nowMs()
        if (!force && pendingPoints.size < pointBatchSize && now - lastPointFlushMs < pointFlushMs) return
        dao.commitRideProgress(r, pendingPoints.toList())
        pendingPoints.clear()
        lastPointFlushMs = now
    }

    private suspend fun restoreRideLocked(fromForeground: Boolean) {
        val s = state ?: return
        if (ride == null) {
            val r = dao.ride(s.activeRideId ?: return) ?: return
            ride = r
            accumulator = DistanceAccumulator().apply { restore(r.rawDistanceM, r.movingMs, r.topSpeedMps, r.fixCount, r.meanAccuracyM) }
            routePreview.clear()
            dao.points(r.id).forEach { routePreview += GpsFix(it.timeMs, it.lat, it.lon, it.accuracyM, it.speedMps) }
            while (routePreview.size > MAX_PREVIEW_POINTS) decimate(routePreview)
        }
        val ms = s.modeState()
        if (ms.ridePaused || locationActive) return
        if (ms.rideInterrupted) { issues += TrackingIssue.RIDE_INTERRUPTED; return }
        val res = if (fromForeground && env.hasLocationPermission()) location.start(::onFixFromSensor) else null
        if (res == LocationFeed.StartResult.OK) {
            locationActive = true
            val r = ride!!
            accumulator.breakSegment()
            ride = r.copy(segment = r.segment + 1)
            dao.saveRide(ride!!)
        } else {
            // Android does not allow GPS to restart from the background (e.g. after a reboot).
            // Keep the ride open, stop its clock at the last fix, and ask the user.
            val r = ride!!
            val closeAt = r.lastFixMs ?: clock.nowMs()
            val updated = r.copy(elapsedMs = r.elapsedMs + (r.activeSinceMs?.let { (closeAt - it).coerceAtLeast(0) } ?: 0), activeSinceMs = null)
            commitRideAndState(updated, s.copy(rideInterrupted = true))
            issues += TrackingIssue.RIDE_INTERRUPTED
            hooks?.onRideInterrupted()
        }
    }

    private fun stopLocationLocked() {
        if (locationActive) location.stop()
        locationActive = false
    }

    private suspend fun commitRideAndState(r: Ride, s: TrackerStateEntity) {
        dao.commitRideProgress(r, pendingPoints.toList())
        pendingPoints.clear()
        dao.saveState(s)
        ride = r; state = s
        publish()
    }

    // =====================================================================================
    // Status
    // =====================================================================================

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(tickerMs)
                mutex.withLock {
                    if (state?.mode() == TrackingMode.WALKING) maybeFlushLocked()
                    publish()
                }
            }
        }
    }

    private fun publish() {
        val s = state
        val r = ride
        val status = LiveStatus(
            engineRunning = running,
            mode = s?.modeState() ?: ModeState(),
            stepSource = stepKind,
            pendingSteps = pendingSteps,
            issues = issues.toSet(),
            ride = r?.let {
                LiveRide(it.id, it.isCalibration, it.rawDistanceM, it.factor, it.elapsedMs, it.activeSinceMs, it.movingMs,
                    lastFix?.speedMps?.toDouble(), lastFix?.accuracyM, it.fixCount, it.meanAccuracyM, routePreview.toList())
            },
        )
        _status.value = status
        hooks?.onStateChanged(status)
    }

    companion object {
        const val RUN_CADENCE_SPM = 140.0
        private const val MAX_PREVIEW_POINTS = 3000

        private fun decimate(list: ArrayList<GpsFix>) {
            val kept = list.filterIndexed { i, _ -> i % 2 == 0 || i == list.lastIndex }
            list.clear(); list.addAll(kept)
        }
    }
}

private fun TrackerStateEntity.mode(): TrackingMode = runCatching { TrackingMode.valueOf(mode) }.getOrDefault(TrackingMode.IDLE)

fun TrackerStateEntity.modeState(): ModeState =
    ModeState(mode(), walkingEnabled, activeRideId, ridePaused, rideInterrupted)
