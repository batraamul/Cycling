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
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.Routes
import com.batraamul.cycling.ui.components.AppCard
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (mode.mode != TrackingMode.CYCLING || ride == null) {
            Text("No ride in progress", style = MaterialTheme.typography.headlineMedium)
            Text("Walking tracking is active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { nav.popBackStack() }) { Text("Back") }
            return@Column
        }
        val title = when {
            mode.rideInterrupted -> "Ride interrupted — Walking Paused"
            mode.ridePaused -> "Ride paused — Walking Paused"
            else -> "Cycling Active — Walking Paused"
        }
        Text(if (ride.isCalibration) "GPS calibration ride" else "Ride", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)

        if (ride.isCalibration) {
            Banner("Ride your measured 1.000 km course at a normal pace. Press “Stop calibration” exactly at the finish point. " +
                "The raw GPS distance is shown (no correction applied).", warning = false)
        }
        if (mode.rideInterrupted) {
            Banner("GPS stopped because the phone restarted or Android closed the app. Distance during the gap was not recorded. " +
                "Resume GPS to continue this ride, or finish it.")
        }

        AppCard {
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
                StatTile("Time", Fmt.duration(ride.elapsedAt(now)), "", Modifier.weight(1f))
                StatTile("Speed", Fmt.speedKmh(if (mode.recordsGps) ride.speedMps else null), "km/h", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val avg = if (ride.movingMs > 0) ride.correctedDistanceM / (ride.movingMs / 1000.0) else null
                StatTile("Avg (moving)", Fmt.speedKmh(avg), "km/h", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
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

        RouteCanvas(ride.route, modifier = Modifier.fillMaxWidth().aspectRatio(1.4f))

        // ---------------- controls ----------------
        if (ride.isCalibration) {
            Button(onClick = { vm.finish { id -> nav.navigate(Routes.calGpsResult(id)) { popUpTo(Routes.CALIBRATION) } } },
                enabled = !mode.ridePaused, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Stop calibration — I'm at the 1.000 km mark", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                mode.rideInterrupted -> Button(onClick = { vm.recover() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Resume GPS") }
                mode.ridePaused -> Button(onClick = { vm.resume() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Resume") }
                else -> OutlinedButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Pause") }
            }
            if (!ride.isCalibration) {
                Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("Finish Cycling") }
            }
        }
        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (ride.isCalibration) "Cancel calibration (discard ride)" else "Discard this ride", color = MaterialTheme.colorScheme.error)
        }
        Text("Pausing does not restart step counting. Walking tracking resumes automatically when you finish or discard the ride.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish this ride?") },
            text = { Text("The ride will be saved and walking tracking will resume.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish { id -> nav.navigate(Routes.rideDetail(id)) { popUpTo(Routes.HOME) } } }) { Text("Finish & save") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep riding") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this ride?") },
            text = { Text("Only this ride and its route are deleted. Your other rides and step history are not affected. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard { nav.popBackStack(Routes.HOME, inclusive = false) } }) {
                Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}
