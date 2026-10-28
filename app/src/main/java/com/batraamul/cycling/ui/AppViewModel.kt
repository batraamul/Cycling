package com.batraamul.cycling.ui

import android.app.Application
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
            is CommandResult.Ok -> { _messages.emit("Ride discarded. Walking tracking resumed."); onDone() }
            is CommandResult.Failed -> _messages.emit(r.message)
        }
    }

    fun setWalkingEnabled(enabled: Boolean) = command { controller.setWalkingEnabled(enabled) }

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

    private fun command(block: suspend () -> CommandResult) = viewModelScope.launch {
        val r = block()
        if (r is CommandResult.Failed) _messages.emit(r.message)
    }
}
