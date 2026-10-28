package com.batraamul.cycling.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.batraamul.cycling.BuildConfig
import com.batraamul.cycling.core.AccelerometerStepDetector
import com.batraamul.cycling.core.GpsFix

/** Sensor callbacks run on this background thread so 50 Hz accelerometer data never touches the UI thread. */
private object SensorThread {
    val handler: Handler by lazy { Handler(HandlerThread("tracking-sensors").apply { start() }.looper) }
}

/**
 * Chooses the most battery-efficient step source available:
 *  1. Hardware step counter (counted by a low-power chip; costs almost nothing).
 *  2. Accelerometer "wake-up" variant: the sensor hub batches samples and wakes the CPU only
 *     every few seconds to deliver them.
 *  3. Ordinary accelerometer + partial wake lock (keeps the CPU awake while walking mode is on;
 *     the most expensive option, used only if nothing else exists).
 */
class AndroidStepSensor(context: Context, private val forceAccelerometer: Boolean) : StepSensor {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var listener: SensorEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun start(listener: StepListener): StepSourceKind {
        stop()
        if (!forceAccelerometer) {
            val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true) ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (counter != null) {
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) = listener.onCounter(e.values[0].toLong())
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                // maxReportLatency: the chip may hold events up to 30 s and deliver them in one batch.
                if (sm.registerListener(l, counter, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000, SensorThread.handler)) {
                    this.listener = l
                    return StepSourceKind.HARDWARE_COUNTER
                }
            }
        }
        val wakeAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val accel = wakeAccel ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return StepSourceKind.NONE
        val detector = AccelerometerStepDetector()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                renewWakeLock()
                val n = detector.onSample(e.values[0], e.values[1], e.values[2], e.timestamp)
                if (n > 0) listener.onSteps(n)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        // 20 000 µs = 50 Hz sampling; batched up to 10 s.
        if (!sm.registerListener(l, accel, 20_000, 10_000_000, SensorThread.handler)) return StepSourceKind.NONE
        this.listener = l
        return if (wakeAccel != null) {
            StepSourceKind.ACCELEROMETER_WAKEUP
        } else {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cycling:steps").apply { setReferenceCounted(false) }
            renewWakeLock(force = true)
            StepSourceKind.ACCELEROMETER_WAKELOCK
        }
    }

    private var wakeLockRenewedAt = 0L

    /**
     * The wake lock is taken with a 10-minute timeout and renewed while sensor events keep
     * arriving. If tracking ever stops unexpectedly, Android releases it automatically.
     */
    private fun renewWakeLock(force: Boolean = false) {
        val wl = wakeLock ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - wakeLockRenewedAt > 5 * 60_000) {
            wl.acquire(10 * 60_000L)
            wakeLockRenewedAt = now
        }
    }

    override fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        fun hasHardwareCounter(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        fun describeSensors(context: Context): String {
            val sm = context.getSystemService(SensorManager::class.java)
            fun line(label: String, type: Int): String {
                val wake = sm.getDefaultSensor(type, true)
                val normal = sm.getDefaultSensor(type)
                return "$label: " + when {
                    wake != null -> "${wake.name} (wake-up, FIFO ${wake.fifoMaxEventCount})"
                    normal != null -> "${normal.name} (FIFO ${normal.fifoMaxEventCount})"
                    else -> "not present"
                }
            }
            return listOf(line("Step counter", Sensor.TYPE_STEP_COUNTER), line("Step detector", Sensor.TYPE_STEP_DETECTOR),
                line("Accelerometer", Sensor.TYPE_ACCELEROMETER)).joinToString("\n")
        }
    }
}

/** GPS via the platform LocationManager (no Google Play Services dependency, no API key). */
class AndroidLocationFeed(private val context: Context) : LocationFeed {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // checked explicitly below
    override fun start(onFix: (GpsFix) -> Unit): LocationFeed.StartResult {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return LocationFeed.StartResult.NO_PERMISSION
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return LocationFeed.StartResult.NO_GPS_HARDWARE
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationFeed.StartResult.GPS_DISABLED
        val l = LocationListener { loc: Location ->
            onFix(GpsFix(loc.time, loc.latitude, loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy else 999f,
                if (loc.hasSpeed()) loc.speed else null))
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, SensorThread.handler.looper)
            listener = l
            LocationFeed.StartResult.OK
        } catch (e: SecurityException) {
            LocationFeed.StartResult.NO_PERMISSION
        }
    }

    override fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }
}

class AndroidEnvironment(private val context: Context) : Environment {
    override fun hasActivityRecognition(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun bootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) { null }

    override fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    override fun appVersion(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

fun isGpsEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)

fun mainLooper(): Looper = Looper.getMainLooper()
