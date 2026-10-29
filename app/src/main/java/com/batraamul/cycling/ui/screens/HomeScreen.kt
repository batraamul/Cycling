package com.batraamul.cycling.ui.screens

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.batraamul.cycling.core.ActivityType
import com.batraamul.cycling.core.Confidence
import com.batraamul.cycling.core.PAUSED_INDEFINITELY
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.data.StepLengthSource
import com.batraamul.cycling.tracking.StepSourceKind
import com.batraamul.cycling.tracking.TrackingIssue
import com.batraamul.cycling.tracking.TrackingService
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.Routes
import com.batraamul.cycling.ui.components.ActionButton
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.OutlineActionButton
import com.batraamul.cycling.ui.components.Banner
import com.batraamul.cycling.ui.components.ConfidenceChip
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.ProgressRing
import com.batraamul.cycling.ui.components.StatTile
import com.batraamul.cycling.ui.components.StatusPill
import com.batraamul.cycling.ui.has
import com.batraamul.cycling.ui.openAppSettings
import com.batraamul.cycling.ui.theme.LocalThemeSpec
import com.batraamul.cycling.ui.theme.NumberStyle
import com.batraamul.cycling.ui.theme.SceneArtwork
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val live by vm.live.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gps by vm.gpsStatus.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val spec = LocalThemeSpec.current
    val s = settings ?: return

    val steps = (today?.steps ?: 0) + live.pendingSteps
    val walkM = (today?.walkDistanceM ?: 0.0) + live.pendingSteps * s.walkStepM
    val liveRide = live.ride?.takeIf { !it.isCalibration }
    val cycleM = (today?.cycleDistanceM ?: 0.0) + (liveRide?.takeIf { it.type == ActivityType.CYCLING }?.correctedDistanceM ?: 0.0)
    val runM = (today?.runDistanceM ?: 0.0) + (liveRide?.takeIf { it.type.isRunLike }?.correctedDistanceM ?: 0.0)
    val totalSteps = steps + (today?.runSteps ?: 0) + (liveRide?.takeIf { it.type.isRunLike }?.steps ?: 0)
    val kcal = (today?.totalKcal ?: 0.0)
    val mode = live.mode
    val sessionLabel = (mode.sessionType ?: ActivityType.CYCLING).label

    var pendingType by remember { mutableStateOf(ActivityType.CYCLING) }
    val start: (ActivityType) -> Unit = { t -> vm.startSession(t) { nav.navigate(Routes.RIDE) } }
    var showPauseDialog by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) start(pendingType)
    }
    val requestStart: (ActivityType) -> Unit = { t ->
        if (!t.usesGps || ctx.has(Manifest.permission.ACCESS_FINE_LOCATION)) start(t)
        else { pendingType = t; locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }

    val (statusText, statusColor) = when {
        mode.inSession && mode.rideInterrupted -> "$sessionLabel interrupted — Walking Paused" to Color(0xFFFFB020)
        mode.inSession && mode.ridePaused -> "$sessionLabel paused — Walking Paused" to Color(0xFFFFB020)
        mode.inSession -> "$sessionLabel Active — Walking Paused" to Color(0xFF4FC3F7)
        mode.stepsPaused -> "Step counting paused" to Color(0xFFFFB020)
        mode.mode == TrackingMode.WALKING && live.issues.isEmpty() && live.engineRunning -> "Walking Tracking Active" to Color(0xFF5BE38C)
        mode.mode == TrackingMode.IDLE && !mode.walkingEnabled -> "Walking tracking off" to Color(0xFFB0B0B0)
        else -> "Tracking needs attention" to Color(0xFFFF6B6B)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(250.dp)) {
                SceneArtwork(spec, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
                Column(Modifier.statusBarsPadding().padding(20.dp)) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelLarge)
                    Text("Today", color = Color.White, style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(10.dp))
                    StatusPill(statusText, statusColor)
                }
            }
        }
        item {
            AppCard(Modifier.padding(horizontal = 16.dp).offset(y = (-36).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(progress = totalSteps.toFloat() / s.dailyStepGoal.coerceAtLeast(1), size = 150.dp, stroke = 13.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Fmt.steps(totalSteps), style = NumberStyle.copy(fontSize = 28.sp), color = MaterialTheme.colorScheme.onSurface)
                            Text("of ${Fmt.steps(s.dailyStepGoal.toLong())} steps", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.padding(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        StatTile("Walking", Fmt.km(walkM), "km", Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary)
                        StatTile("Cycling", Fmt.km(cycleM), "km", Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Running", Fmt.km(runM), "km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                    StatTile("Calories (est.)", Fmt.kcal(kcal), "kcal", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                }
                val activeMs = (today?.cycleElapsedMs ?: 0) + (today?.runElapsedMs ?: 0) + (today?.exerciseMs ?: 0)
                if (activeMs > 0 || (today?.activities ?: 0) > 0) {
                    Spacer(Modifier.height(8.dp))
                    StatTile("Activity time · ${today?.activities ?: 0} sessions", Fmt.durationShort(activeMs), "", Modifier.fillMaxWidth(),
                        accent = MaterialTheme.colorScheme.secondary)
                }
                if ((today?.runSteps ?: 0) > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("Steps include ${Fmt.steps(today?.runSteps ?: 0)} from running/treadmill sessions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IssueBanners(live.issues, mode.mode, live.engineRunning, onFixPermissions = { ctx.openAppSettings() },
                    onRestart = { vm.ensureTracking() }, onOpenRide = { nav.navigate(Routes.RIDE) })

                if (mode.inSession) {
                    val r = live.ride
                    val t = r?.type ?: ActivityType.CYCLING
                    AppCard(Modifier.clickable { nav.navigate(Routes.RIDE) }) {
                        Text(if (r?.isCalibration == true) "Calibration ride in progress" else "${t.icon} ${t.label} in progress",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val detail = listOfNotNull(
                            "${Fmt.km(r?.correctedDistanceM ?: 0.0)} km".takeIf { t.hasDistance },
                            "${Fmt.steps(r?.steps ?: 0)} steps".takeIf { t.countsSteps },
                        ).joinToString(" · ")
                        Text((if (detail.isEmpty()) "" else "$detail · ") + "walking step counting is paused until you finish.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        ActionButton("Open ${t.label.lowercase()}", onClick = { nav.navigate(Routes.RIDE) }, Modifier.fillMaxWidth(), icon = t.icon)
                    }
                } else {
                    ActivityPicker(onStart = requestStart)
                }

                if (mode.stepsPaused) {
                    val until = mode.stepsPausedUntilMs!!
                    Banner(
                        if (until == PAUSED_INDEFINITELY) "Step counting is paused until you resume it. Steps are not being counted."
                        else "Step counting is paused until ${DateFormat.getTimeFormat(ctx).format(Date(until))}, then resumes automatically.",
                    ) { TextButton(onClick = { vm.resumeSteps() }) { Text("Resume", fontWeight = FontWeight.Bold) } }
                } else if (mode.mode == TrackingMode.WALKING) {
                    OutlineActionButton("Pause step counting (motorbike, car, bus…)", onClick = { showPauseDialog = true },
                        modifier = Modifier.fillMaxWidth(), icon = "⏸")
                }

                AppCard(Modifier.clickable { nav.navigate(Routes.CALIBRATION) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Calibration Centre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { nav.navigate(Routes.CALIBRATION) }) { Text("Open") }
                    }
                    KeyValue("Step length", Fmt.stepCm(s.walkStepM) + if (s.walkStepSource == StepLengthSource.HEIGHT_ESTIMATE) " (estimated)" else "")
                    KeyValue("GPS distance", if (s.gpsFactor == 1.0) "Default calculation" else "Factor ${Fmt.factor(s.gpsFactor)} (${Fmt.pct(s.gpsFactor)})")
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfidenceChip(if (s.walkStepSource == StepLengthSource.HEIGHT_ESTIMATE) Confidence.NONE
                            else s.walkStepConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() })
                        ConfidenceChip(gps?.confidence)
                    }
                }

                AppCard {
                    Text("How today's steps are counted", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (live.stepSource) {
                            StepSourceKind.HARDWARE_COUNTER -> "Hardware step counter (lowest battery use)."
                            StepSourceKind.ACCELEROMETER_WAKEUP -> "Accelerometer with on-chip batching (this phone has no hardware step counter)."
                            StepSourceKind.ACCELEROMETER_WAKELOCK -> "Accelerometer with a wake lock (no low-power sensor available; uses more battery)."
                            StepSourceKind.NONE -> "No usable step sensor."
                            null -> if (mode.inSession) "Paused during the activity." else "Not running."
                        } + " Walking distance = steps × ${Fmt.stepCm(s.walkStepM)}. Steps are saved in batches about once a minute to save battery.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (showPauseDialog) {
        PauseStepsDialog(onPick = { showPauseDialog = false; vm.pauseSteps(it) }, onDismiss = { showPauseDialog = false })
    }
}

/** Start buttons for every activity type: cycling large (the main use), the rest as a 2 × 2 grid. */
@Composable
private fun ActivityPicker(onStart: (ActivityType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Start Cycling", onClick = { onStart(ActivityType.CYCLING) }, Modifier.fillMaxWidth(), icon = ActivityType.CYCLING.icon, height = 64.dp)
        val others = listOf(ActivityType.RUNNING, ActivityType.JOGGING, ActivityType.TREADMILL, ActivityType.EXERCISE)
        others.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    OutlineActionButton(t.label, onClick = { onStart(t) }, Modifier.weight(1f), icon = t.icon,
                        color = if (t.usesGps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.let {
                            if (it.luminance() > 0.6f) MaterialTheme.colorScheme.primary else it })
                }
            }
        }
        Text("Running and jogging use GPS + steps · Treadmill uses steps × your step length · Exercise records time and calories. " +
            "Daily walking pauses during any activity, so nothing is counted twice.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PauseStepsDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause step counting") },
        text = {
            Column {
                Text("Use this while riding a motorbike or travelling by car/bus: vibration and bumps can be counted as steps. " +
                    "Steps already counted today are kept. (For a bicycle ride, use Start Cycling instead — it pauses steps and records the ride.)",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours").forEach { (m, label) ->
                    TextButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
                TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Until I resume") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IssueBanners(issues: Set<TrackingIssue>, mode: TrackingMode, engineRunning: Boolean,
                         onFixPermissions: () -> Unit, onRestart: () -> Unit, onOpenRide: () -> Unit) {
    if (TrackingIssue.ACTIVITY_PERMISSION_MISSING in issues) {
        Banner("Physical-activity permission is off, so steps are not being counted.") { TextButton(onClick = onFixPermissions) { Text("Fix") } }
    }
    if (TrackingIssue.NO_STEP_SENSOR in issues) Banner("No usable step sensor was found on this phone.")
    if (TrackingIssue.RIDE_INTERRUPTED in issues) {
        Banner("Your activity was interrupted (phone restart or Android closed the app). GPS is stopped and steps are not counted until you resume or finish it.") {
            TextButton(onClick = onOpenRide) { Text("Open") }
        }
    }
    if (mode != TrackingMode.IDLE && (!engineRunning || !TrackingService.running)) {
        Banner("Background tracking is not running right now.") { TextButton(onClick = onRestart) { Text("Restart") } }
    }
}
