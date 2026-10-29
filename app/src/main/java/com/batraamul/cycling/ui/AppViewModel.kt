package com.batraamul.cycling.ui

import android.app.Application
import android.net.Uri
import com.batraamul.cycling.backup.BackupPrefs
import com.batraamul.cycling.backup.DriveBackup
import com.batraamul.cycling.data.BackupPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batraamul.cycling.CyclingApp
import com.batraamul.cycling.core.Confidence
import com.batraamul.cycling.core.GpsCalibrationEvaluation
import com.batraamul.cycling.data.DaySummary
import com.batraamul.cycling.data.GpsCalibrationStatus
import com.batraamul.cycling.data.SettingsEntity
import com.batraamul.cycling.tracking.CommandResult
import com.batraamul.cycling.tracking.LiveStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Screen state + user actions. A ViewModel survives screen rotation and navigation, so a
 * "Finish ride" command is never cancelled half-way because the user left the screen.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as CyclingApp).container
    val repo = container.repository
    private val controller = container.controller

    val settings: StateFlow<SettingsEntity?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val live: StateFlow<LiveStatus> = controller.status
    val today: StateFlow<DaySummary?> = repo.today.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val gpsStatus: StateFlow<GpsCalibrationStatus?> = repo.gpsStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun ensureTracking() {
        if (settings.value?.onboardingDone == true) controller.ensureRunning()
    }

    fun completeOnboarding(heightCm: Int, weightKg: Double) = viewModelScope.launch {
        repo.updateSettings {
            val heightDefault = com.batraamul.cycling.core.StepCalibrationMath.defaultFromHeight(heightCm)
            it.copy(onboardingDone = true, heightCm = heightCm, weightKg = weightKg,
                // The height formula is used only until the user saves a measured value.
                walkStepM = if (it.walkStepCalibratedAtMs == null) heightDefault else it.walkStepM)
        }
        controller.setWalkingEnabled(true)   // walking tracking ON by default after onboarding
        controller.ensureRunning()
    }

    fun startCycling(isCalibration: Boolean, onStarted: (Long) -> Unit) = viewModelScope.launch {
        when (val r = controller.startCycling(isCalibration)) {
            is CommandResult.Ok -> onStarted(r.rideId ?: 0)
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun startSession(type: com.batraamul.cycling.core.ActivityType, onStarted: (Long) -> Unit) = viewModelScope.launch {
        when (val r = controller.startSession(type)) {
            is CommandResult.Ok -> onStarted(r.rideId ?: 0)
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun pause() = command { controller.pause() }
    fun resume() = command { controller.resume() }
    fun recover() = command { controller.recover() }

    fun finish(onDone: (rideId: Long) -> Unit) = viewModelScope.launch {
        when (val r = controller.finish()) {
            is CommandResult.Ok -> onDone(r.rideId ?: 0)
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        when (val r = controller.discard()) {
            is CommandResult.Ok -> { _messages.emit("Activity discarded. Walking tracking resumed."); onDone() }
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun setWalkingEnabled(enabled: Boolean) = command { controller.setWalkingEnabled(enabled) }

    /** Pause step counting for [minutes] (null = until the user resumes), e.g. on a motorbike. */
    fun pauseSteps(minutes: Int?) = viewModelScope.launch {
        val until = minutes?.let { System.currentTimeMillis() + it * 60_000L } ?: com.batraamul.cycling.core.PAUSED_INDEFINITELY
        when (val r = controller.pauseSteps(until)) {
            is CommandResult.Ok -> _messages.emit(if (minutes == null) "Step counting paused until you resume." else "Step counting paused for ${minutes} min.")
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun resumeSteps() = viewModelScope.launch {
        when (val r = controller.resumeSteps()) {
            is CommandResult.Ok -> _messages.emit("Step counting resumed.")
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun updateSettings(change: (SettingsEntity) -> SettingsEntity) = viewModelScope.launch { repo.updateSettings(change) }

    fun setStepSource(source: String) = viewModelScope.launch {
        repo.updateSettings { it.copy(stepSource = source) }
        controller.restartStepSensor()
    }

    fun saveStepLength(kind: String, method: String, inputs: String, resultM: Double, confidence: Confidence, onSaved: () -> Unit) =
        viewModelScope.launch {
            repo.saveStepLength(kind, method, inputs, resultM, confidence)
            _messages.emit("Step length saved: ${Fmt.stepCm(resultM)}")
            onSaved()
        }

    fun saveGpsCalibration(rideId: Long, e: GpsCalibrationEvaluation, apply: Boolean, onSaved: () -> Unit) = viewModelScope.launch {
        val applied = repo.saveGpsCalibration(rideId, e, apply)
        _messages.emit(if (applied) "GPS correction applied to future rides." else "Calibration recorded without changing the GPS calculation.")
        onSaved()
    }

    fun clearRunStep() = viewModelScope.launch {
        repo.clearRunStepLength()
        _messages.emit("Running step length removed. The walking value is used for all steps.")
    }

    fun resetGps() = viewModelScope.launch {
        repo.resetGpsCalibration()
        _messages.emit("GPS distance reset to the default calculation (factor 1.0000).")
    }

    // =============================== backup & restore ===============================

    data class BackupUiState(
        val fileLabel: String? = null,
        val onDrive: Boolean = false,
        val auto: Boolean = false,
        val lastSuccessMs: Long = 0,
        val lastSizeBytes: Long = 0,
        val lastError: String? = null,
        val busy: Boolean = false,
    )

    private val app = getApplication<Application>()
    private val prefs = BackupPrefs(app)
    private val _backup = MutableStateFlow(BackupUiState())
    val backup: StateFlow<BackupUiState> = _backup
    private val _restorePreview = MutableStateFlow<Pair<Uri, BackupPreview>?>(null)
    val restorePreview: StateFlow<Pair<Uri, BackupPreview>?> = _restorePreview

    fun refreshBackupState(busy: Boolean = false) {
        _backup.value = BackupUiState(prefs.fileLabel, DriveBackup.isOnDrive(prefs.uri), prefs.auto, prefs.lastSuccessMs,
            prefs.lastSizeBytes, prefs.lastError, busy)
    }

    /** The user created/picked the backup file (ideally in Google Drive): remember it, back up now, turn on daily backups. */
    fun backupFileChosen(uri: Uri) = viewModelScope.launch {
        DriveBackup.rememberFile(app, uri)
        prefs.auto = true
        DriveBackup.schedule(app, true)
        runBackup()
    }

    fun backupNow() = viewModelScope.launch { runBackup() }

    private suspend fun runBackup() {
        refreshBackupState(busy = true)
        val outcome = withContext(Dispatchers.IO) { DriveBackup.backupNow(app, manual = true) }
        refreshBackupState()
        _messages.emit(when (outcome) {
            is DriveBackup.Outcome.Saved -> "Backup saved (${Fmt.bytes(outcome.bytes)}). Google Drive uploads it when the phone is online."
            is DriveBackup.Outcome.Failed -> "Backup failed: ${outcome.message}"
            DriveBackup.Outcome.SkippedEmpty -> "Nothing to back up yet."
        })
    }

    fun setAutoBackup(on: Boolean) {
        prefs.auto = on
        DriveBackup.schedule(app, on)
        refreshBackupState()
    }

    /** Step 1 of restore: read the chosen file and show what it contains. Nothing is changed yet. */
    fun openRestoreFile(uri: Uri) = viewModelScope.launch {
        try {
            val preview = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.buffered()?.use { container.backup.preview(it) }
                    ?: throw IllegalStateException("The file could not be opened.")
            }
            _restorePreview.value = uri to preview
        } catch (e: Exception) {
            _messages.emit(e.message ?: "The file could not be read.")
        }
    }

    fun cancelRestore() { _restorePreview.value = null }

    /** Step 2: merge the backup into this phone's data (after the user confirmed). */
    fun confirmRestore() = viewModelScope.launch {
        val (uri, _) = _restorePreview.value ?: return@launch
        _restorePreview.value = null
        if (live.value.mode.inSession) { _messages.emit("Finish the current activity before restoring a backup."); return@launch }
        try {
            val r = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.buffered()?.use { container.backup.restore(it) }
                    ?: throw IllegalStateException("The file could not be opened.")
            }
            controller.restartStepSensor()
            _messages.emit("Restored: ${r.daysAdded} days added, ${r.daysUpdated} updated, ${r.sessionsAdded} activities added" +
                (if (r.sessionsAlreadyPresent > 0) " (${r.sessionsAlreadyPresent} already here)" else "") + ".")
        } catch (e: Exception) {
            _messages.emit("Restore failed, nothing was changed: ${e.message}")
        }
    }

    private fun command(block: suspend () -> CommandResult) = viewModelScope.launch {
        val r = block()
        if (r is CommandResult.Failed) _messages.emit(r.message)
    }
}
