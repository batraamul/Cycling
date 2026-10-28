package com.batraamul.cycling.tracking

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * The UI's single entry point for tracking commands. It forwards each command to the engine
 * and then tells the service to refresh its foreground type and notification.
 */
class TrackingController(private val context: Context, private val engine: TrackingEngine) {

    val status get() = engine.status

    /** Called whenever the app comes to the foreground: (re)starts tracking if it should be running. */
    fun ensureRunning() = send(TrackingService.ACTION_RESTORE, fromForeground = true)

    fun startServiceFromBackground() = send(TrackingService.ACTION_RESTORE, fromForeground = false)

    suspend fun startCycling(isCalibration: Boolean): CommandResult {
        if (!engine.status.value.engineRunning) engine.restore(fromForeground = true)
        return engine.startCycling(isCalibration).also { refresh() }
    }

    suspend fun pause() = engine.pauseRide().also { refresh() }
    suspend fun resume() = engine.resumeRide().also { refresh() }
    suspend fun recover() = engine.recoverRide().also { refresh() }
    suspend fun finish() = engine.finishRide().also { refresh() }
    suspend fun discard() = engine.discardRide().also { refresh() }
    suspend fun setWalkingEnabled(enabled: Boolean) = engine.setWalkingEnabled(enabled).also {
        if (enabled) ensureRunning() else refresh()
    }
    suspend fun restartStepSensor() = engine.restartStepSensor()
    suspend fun pauseSteps(untilMs: Long) = engine.pauseSteps(untilMs).also { refresh() }
    suspend fun resumeSteps() = engine.resumeSteps().also { refresh() }

    private fun refresh() = send(TrackingService.ACTION_UPDATE, fromForeground = true)

    private fun send(action: String, fromForeground: Boolean) {
        val i = Intent(context, TrackingService::class.java).setAction(action)
            .putExtra(TrackingService.EXTRA_FROM_FOREGROUND, fromForeground)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException etc. The UI shows "tracking interrupted".
        }
    }
}
