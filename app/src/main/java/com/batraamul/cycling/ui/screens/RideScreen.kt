package com.batraamul.cycling.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.batraamul.cycling.core.ActivityType
import com.batraamul.cycling.core.Calories
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.Routes
import com.batraamul.cycling.ui.components.ActionButton
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.OutlineActionButton
import com.batraamul.cycling.ui.components.Banner
import com.batraamul.cycling.ui.components.RouteCanvas
import com.batraamul.cycling.ui.components.StatTile
import com.batraamul.cycling.ui.theme.NumberStyle
import kotlinx.coroutines.delay

/** A clock that ticks once per second only while the screen is actually drawn (frames pause in the background). */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }            // suspends while the app is not visible
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

@Composable
fun RideScreen(vm: AppViewModel, nav: NavController) {
    val live by vm.live.collectAsStateWithLifecycle()
    val now = rememberNow()
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val ride = live.ride
    val mode = live.mode
    val settings by vm.settings.collectAsStateWithLifecycle()
    val weightKg = settings?.weightKg ?: 70.0
    val stepInfo = (settings?.walkStepM ?: 0.7) to (settings?.runStepM != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!mode.inSession || ride == null) {
            Text("No activity in progress", style = MaterialTheme.typography.headlineMedium)
            Text("Walking tracking is active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton("Back", onClick = { nav.popBackStack() })
            return@Column
        }
        val t = ride.type
        val title = when {
            mode.rideInterrupted -> "${t.label} interrupted — Walking Paused"
            mode.ridePaused -> "${t.label} paused — Walking Paused"
            else -> "${t.label} Active — Walking Paused"
        }
        Text(if (ride.isCalibration) "GPS calibration ride" else "${t.icon}  ${t.label}", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.titleLarge)

        if (ride.isCalibration) {
            Banner("Ride your measured 1.000 km course at a normal pace. Press “Stop calibration” exactly at the finish point. " +
                "The raw GPS distance is shown (no correction applied).", warning = false)
        }
        if (mode.rideInterrupted) {
            Banner("GPS stopped because the phone restarted or Android closed the app. Distance during the gap was not recorded. " +
                "Resume GPS to continue, or finish.")
        }
        if (t == ActivityType.TREADMILL) {
            Banner("Treadmill distance = steps × step length (${if (stepInfo.second) "running" else "walking"} value from the Calibration Centre). " +
                "Keep the phone in your pocket, not on the treadmill console.", warning = false)
        }

        AppCard {
            val elapsed = ride.elapsedAt(now)
            if (t == ActivityType.EXERCISE) {
                Text("Workout time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.duration(elapsed), style = NumberStyle.copy(fontSize = 64.sp), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                StatTile("Calories so far (est.)", Fmt.kcal(Calories.exerciseKcal(weightKg, elapsed)), "kcal", Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(if (ride.isCalibration) "Raw GPS distance" else "Distance", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (ride.isCalibration) Fmt.km3(ride.rawDistanceM) else Fmt.km(ride.correctedDistanceM),
                        style = NumberStyle.copy(fontSize = 64.sp), color = MaterialTheme.colorScheme.onSurface)
                    Text(" km", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                }
                if (!ride.isCalibration && ride.factor != 1.0) {
                    Text("Raw GPS ${Fmt.km3(ride.rawDistanceM)} km × calibration ${Fmt.factor(ride.factor)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Time", Fmt.duration(elapsed), "", Modifier.weight(1f))
                    if (t.usesGps) {
                        if (t == ActivityType.CYCLING) StatTile("Speed", Fmt.speedKmh(if (mode.recordsGps) ride.speedMps else null), "km/h", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                        else StatTile("Pace", Fmt.pace(if (mode.recordsGps) ride.speedMps else null), "/km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                    } else {
                        StatTile("Avg pace", Fmt.pace(if (elapsed > 0) ride.correctedDistanceM / (elapsed / 1000.0) else null), "/km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (t.countsSteps) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Steps", Fmt.steps(ride.steps), "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                        val cadence = if (elapsed > 60_000) ride.steps / (elapsed / 60_000.0) else null
                        StatTile("Cadence (avg)", cadence?.let { "${it.toInt()}" } ?: "–", "spm", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (t.usesGps) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val avg = if (ride.movingMs > 0) ride.correctedDistanceM / (ride.movingMs / 1000.0) else null
                        if (t == ActivityType.CYCLING) StatTile("Avg (moving)", Fmt.speedKmh(avg), "km/h", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                        else StatTile("Avg pace", Fmt.pace(avg), "/km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                        val acc = ride.accuracyM
                        val accColor = when { acc == null -> Color.Gray; acc <= 10 -> Color(0xFF2E9E5B); acc <= 20 -> Color(0xFFE0A100); else -> Color(0xFFD9534F) }
                        StatTile("GPS accuracy", if (acc == null || !mode.recordsGps) "–" else "±${acc.toInt()}", "m", Modifier.weight(1f), accent = accColor)
                    }
                    if (mode.recordsGps && ride.fixCount == 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Waiting for GPS fix… stay in the open sky for a few seconds.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (t.usesGps) RouteCanvas(ride.route, modifier = Modifier.fillMaxWidth().aspectRatio(1.4f))

        // ---------------- controls ----------------
        if (ride.isCalibration) {
            ActionButton("Stop calibration — I'm at the 1.000 km mark",
                onClick = { vm.finish { id -> nav.navigate(Routes.calGpsResult(id)) { popUpTo(Routes.CALIBRATION) } } },
                enabled = !mode.ridePaused, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                mode.rideInterrupted -> ActionButton("Resume GPS", onClick = { vm.recover() }, Modifier.weight(1f), icon = "▶")
                mode.ridePaused -> ActionButton("Resume", onClick = { vm.resume() }, Modifier.weight(1f), icon = "▶")
                else -> OutlineActionButton("Pause", onClick = { vm.pause() }, Modifier.weight(1f), icon = "⏸", height = 60.dp)
            }
            if (!ride.isCalibration) {
                ActionButton("Finish ${t.label}", onClick = { confirmFinish = true }, Modifier.weight(1f), icon = "✓",
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (ride.isCalibration) "Cancel calibration (discard ride)" else "Discard this ${t.label.lowercase()} session",
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Text("Pausing does not restart walking step counting. Walking tracking resumes automatically when you finish or discard.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish this ${ride?.type?.label?.lowercase() ?: "activity"}?") },
            text = { Text("It will be saved and walking tracking will resume.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish { id -> nav.navigate(Routes.rideDetail(id)) { popUpTo(Routes.HOME) } } }) { Text("Finish & save") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this session?") },
            text = { Text("Only this session (and its route) is deleted. Your other activities and step history are not affected. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard { nav.popBackStack(Routes.HOME, inclusive = false) } }) {
                Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}
