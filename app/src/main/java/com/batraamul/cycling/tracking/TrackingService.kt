package com.batraamul.cycling.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.batraamul.cycling.CyclingApp
import com.batraamul.cycling.MainActivity
import com.batraamul.cycling.R
import android.text.format.DateFormat
import com.batraamul.cycling.core.ActivityType
import com.batraamul.cycling.core.PAUSED_INDEFINITELY
import com.batraamul.cycling.core.SystemClock
import java.util.Date
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.core.key
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * The foreground service: Android's way of saying "this app is doing something the user
 * cares about right now — don't kill it". It shows the persistent notification and keeps
 * the process alive with the screen off. All real logic lives in [TrackingEngine].
 *
 * Foreground-service types (required since Android 14):
 *   - health   : step counting (allowed to restart after reboot)
 *   - location : GPS during a ride (only while the ride was started by the user in the app)
 */
class TrackingService : LifecycleService(), EngineHooks {

    private val container by lazy { (application as CyclingApp).container }
    private val engine get() = container.engine
    private var currentTypes = -1
    private var latest: LiveStatus = LiveStatus()
    private var todaySteps = 0L
    private var todayDistanceM = 0.0

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        engine.hooks = this
        running = true
        observeForNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Must call startForeground promptly after startForegroundService(); start with the safe type.
        if (!goForeground(engine.status.value)) { stopSelf(); return START_NOT_STICKY }

        when (intent?.action ?: ACTION_RESTORE) {
            ACTION_RESTORE -> {
                val fromForeground = intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) ?: false
                lifecycleScope.launch {
                    val keep = engine.restore(fromForeground)
                    if (!keep) stopTracking() else goForeground(engine.status.value)
                }
            }
            ACTION_UPDATE -> {
                val s = engine.status.value
                if (s.mode.mode == TrackingMode.IDLE) stopTracking() else goForeground(s)
            }
            ACTION_PAUSE -> lifecycleScope.launch { engine.pauseRide() }
            ACTION_RESUME -> lifecycleScope.launch { engine.resumeRide() }
            ACTION_PAUSE_STEPS -> lifecycleScope.launch { engine.pauseSteps(System.currentTimeMillis() + 60 * 60_000L) }
            ACTION_RESUME_STEPS -> lifecycleScope.launch { engine.resumeSteps() }
        }
        // START_STICKY: if Android kills the process, it recreates the service (with a null intent).
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (engine.hooks === this) engine.hooks = null
        super.onDestroy()
    }

    // ---------------- EngineHooks ----------------

    override fun onStateChanged(status: LiveStatus) {
        latest = status
        val wantLocation = wantsLocationType(status)
        if (wantLocation != ((currentTypes and LOCATION_BIT) != 0) && currentTypes != -1) goForeground(status)
    }

    override fun onRideInterrupted() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ride interrupted")
            .setContentText("GPS stopped (the phone restarted or Android closed the app). Tap to resume or finish the ride.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(openAppIntent(this))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    override fun onSessionFinished(discarded: Boolean) {
        if (!discarded) com.batraamul.cycling.backup.DriveBackup.backupSoon(this)
    }

    // ---------------- foreground handling ----------------

    /** Location type only for GPS activities: treadmill/exercise run as a plain health service. */
    private fun wantsLocationType(s: LiveStatus) =
        s.mode.inSession && (s.mode.sessionType ?: ActivityType.CYCLING).usesGps && !s.mode.rideInterrupted

    private fun goForeground(s: LiveStatus): Boolean {
        val env = container.environment
        var types = 0
        if (Build.VERSION.SDK_INT >= 34 && env.hasActivityRecognition()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (wantsLocationType(s) && env.hasLocationPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34 && types == 0) return false   // nothing we are allowed to do in the background
        return try {
            ServiceCompat.startForeground(this, NOTIF_ONGOING, buildNotification(s), types)
            currentTypes = types
            true
        } catch (e: Exception) {
            // e.g. a location-type start is refused while the app is in the background.
            if (types and LOCATION_BIT != 0) {
                val fallback = types and LOCATION_BIT.inv()
                try {
                    ServiceCompat.startForeground(this, NOTIF_ONGOING, buildNotification(s), fallback)
                    currentTypes = fallback
                    true
                } catch (e2: Exception) { false }
            } else false
        }
    }

    private fun stopTracking() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeForNotification() {
        val dao = container.db.tracking()
        val todayKey = flow { while (true) { emit(SystemClock.today().key()); delay(60_000) } }.distinctUntilChanged()
        val today = todayKey.flatMapLatest { dao.dayFlow(it) }.map { (it?.steps ?: 0L) to (it?.walkDistanceM ?: 0.0) }
        lifecycleScope.launch {
            combine(engine.status, today) { s, t -> s to t }.collect { (s, t) ->
                todaySteps = t.first; todayDistanceM = t.second
                if (currentTypes != -1) {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, buildNotification(s))
                }
                delay(2_000) // at most one notification refresh every 2 s
            }
        }
    }

    private fun buildNotification(s: LiveStatus): Notification {
        val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
        val b = NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(this))
        val ride = s.ride
        when {
            s.mode.inSession && ride != null -> {
                val t = ride.type
                val km = String.format(Locale.getDefault(), "%.2f km", ride.correctedDistanceM / 1000)
                val detail = listOfNotNull(
                    km.takeIf { t.hasDistance },
                    "${nf.format(ride.steps)} steps".takeIf { t.countsSteps },
                ).joinToString(" · ").ifEmpty { "Timer running" }
                when {
                    s.mode.rideInterrupted -> b.setContentTitle("${t.label} interrupted — Walking Paused").setContentText("$detail · Open the app to resume GPS or finish")
                    s.mode.ridePaused -> b.setContentTitle("${t.label} paused — Walking Paused").setContentText(detail)
                        .addAction(0, "Resume", serviceIntent(ACTION_RESUME))
                    else -> b.setContentTitle("${t.icon} ${t.label} Active — Walking Paused").setContentText(detail)
                        .setUsesChronometer(true).setWhen(System.currentTimeMillis() - ride.elapsedAt(System.currentTimeMillis()))
                        .addAction(0, "Pause", serviceIntent(ACTION_PAUSE))
                }
            }
            s.mode.stepsPaused -> {
                val until = s.mode.stepsPausedUntilMs!!
                b.setContentTitle("Step counting paused")
                    .setContentText(if (until == PAUSED_INDEFINITELY) "Until you resume · ${nf.format(todaySteps)} steps today"
                        else "Resumes at ${DateFormat.getTimeFormat(this).format(Date(until))} · ${nf.format(todaySteps)} steps today")
                    .addAction(0, "Resume steps", serviceIntent(ACTION_RESUME_STEPS))
            }
            s.mode.mode == TrackingMode.WALKING -> {
                val steps = todaySteps + s.pendingSteps
                val issue = when {
                    TrackingIssue.ACTIVITY_PERMISSION_MISSING in s.issues -> "Physical-activity permission needed — tap to fix"
                    TrackingIssue.NO_STEP_SENSOR in s.issues -> "No step sensor available"
                    else -> null
                }
                b.setContentTitle(if (issue == null) "Walking Tracking Active" else "Walking tracking needs attention")
                    .setContentText(issue ?: "${nf.format(steps)} steps today · ${String.format(Locale.getDefault(), "%.2f km", todayDistanceM / 1000)}")
                if (issue == null) b.addAction(0, "Pause steps 1 h", serviceIntent(ACTION_PAUSE_STEPS))
            }
            else -> b.setContentTitle("Tracking starting…")
        }
        return b.build()
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION_RESTORE = "com.batraamul.cycling.RESTORE"
        const val ACTION_UPDATE = "com.batraamul.cycling.UPDATE"
        const val ACTION_PAUSE = "com.batraamul.cycling.PAUSE"
        const val ACTION_RESUME = "com.batraamul.cycling.RESUME"
        const val ACTION_PAUSE_STEPS = "com.batraamul.cycling.PAUSE_STEPS"
        const val ACTION_RESUME_STEPS = "com.batraamul.cycling.RESUME_STEPS"
        const val EXTRA_FROM_FOREGROUND = "fromForeground"
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2
        private const val LOCATION_BIT = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        /** True while the service exists in this process (used for the "tracking interrupted" indicator). */
        @Volatile var running = false
            private set

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_TRACKING, context.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        }

        fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
