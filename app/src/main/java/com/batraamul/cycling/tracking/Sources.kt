package com.batraamul.cycling.tracking

import com.batraamul.cycling.core.GpsFix

/*
 * Small interfaces between the tracking logic and the hardware. The real implementations
 * talk to Android's SensorManager / LocationManager; tests plug in fakes.
 */

enum class StepSourceKind { HARDWARE_COUNTER, ACCELEROMETER_WAKEUP, ACCELEROMETER_WAKELOCK, NONE }

interface StepListener {
    /** Hardware counter: cumulative steps since boot. */
    fun onCounter(raw: Long)
    /** Accelerometer detector: newly detected steps. */
    fun onSteps(count: Int)
}

interface StepSensor {
    /** Starts delivering events. Returns the source actually in use (NONE = no usable sensor). */
    fun start(listener: StepListener): StepSourceKind
    fun stop()
}

interface LocationFeed {
    enum class StartResult { OK, NO_PERMISSION, GPS_DISABLED, NO_GPS_HARDWARE }
    fun start(onFix: (GpsFix) -> Unit): StartResult
    fun stop()
}

interface Environment {
    fun hasActivityRecognition(): Boolean
    fun hasLocationPermission(): Boolean
    fun bootCount(): Int?
    fun deviceModel(): String
    fun appVersion(): String
}

/** Things only the Android Service can do (notification, foreground type). */
interface EngineHooks {
    fun onStateChanged(status: LiveStatus)
    fun onRideInterrupted()
    /** A session was finished or discarded (used to trigger an automatic backup). */
    fun onSessionFinished(discarded: Boolean) {}
}
