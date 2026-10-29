package com.batraamul.cycling.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batraamul.cycling.core.ActivityType
import com.batraamul.cycling.core.Calories
import com.batraamul.cycling.core.GpsFix
import com.batraamul.cycling.data.Ride
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.RouteCanvas
import com.batraamul.cycling.ui.components.StatTile

/** Activity list (all types), loaded 30 at a time (never all sessions at once). */
@Composable
fun HistoryScreen(vm: AppViewModel, onOpen: (Long) -> Unit) {
    val count by vm.repo.completedRideCount.collectAsStateWithLifecycle(0)
    val rides = remember { mutableStateListOf<Ride>() }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    suspend fun loadMore() {
        if (loading || endReached) return
        loading = true
        val page = vm.repo.ridesPage(offset = rides.size)
        rides += page
        endReached = page.size < 30
        loading = false
    }
    LaunchedEffect(count) { rides.clear(); endReached = false; loadMore() }   // refresh when a ride is added/removed

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Activities", style = MaterialTheme.typography.headlineMedium) }
        if (rides.isEmpty() && !loading) item {
            Text("No activities yet. Start cycling, running, jogging, treadmill or exercise from the Today screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(rides, key = { it.id }) { r -> RideRow(r) { onOpen(r.id) } }
        if (!endReached && rides.isNotEmpty()) item {
            LaunchedEffect(rides.size) { loadMore() }   // reached the bottom: fetch the next page
            Text("Loading…", modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun RideRow(r: Ride, onClick: () -> Unit) {
    val t = r.type
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(t.icon, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${t.label} · ${Fmt.dateTime(r.startTimeMs)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rowHeadline(r), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(rowDetail(r) + if (r.isCalibration) " · Calibration" else "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun rowHeadline(r: Ride): String =
    if (r.type.hasDistance) "${Fmt.km(r.correctedDistanceM)} km · ${Fmt.duration(r.elapsedMs)}" else Fmt.duration(r.elapsedMs)

private fun rowDetail(r: Ride): String {
    val avg = if (r.movingMs > 0) r.correctedDistanceM / (r.movingMs / 1000.0) else null
    return when (r.type) {
        ActivityType.CYCLING -> "Avg ${Fmt.speedKmh(avg)} km/h"
        ActivityType.EXERCISE -> "${Fmt.kcal(r.kcal)} kcal (est.)"
        else -> "${Fmt.steps(r.steps)} steps · pace ${Fmt.pace(if (r.elapsedMs > 0) r.correctedDistanceM / (r.elapsedMs / 1000.0) else null)} /km"
    }
}

@Composable
fun RideDetailScreen(vm: AppViewModel, rideId: Long, onBack: () -> Unit) {
    val ride by remember(rideId) { vm.repo.rideFlow(rideId) }.collectAsStateWithLifecycle(null)
    var points by remember { mutableStateOf<List<GpsFix>>(emptyList()) }
    var segments by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(rideId) {
        val pts = vm.repo.routePoints(rideId)
        points = pts.map { GpsFix(it.timeMs, it.lat, it.lon, it.accuracyM, it.speedMps) }
        segments = pts.map { it.segment }
    }
    val r = ride
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        if (r == null) { Text("This activity no longer exists."); return@Column }
        val t = r.type
        Text(if (r.isCalibration) "Calibration ride" else "${t.icon}  ${t.label}", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(Fmt.dateTime(r.startTimeMs), style = MaterialTheme.typography.headlineMedium)
        if (t.usesGps) RouteCanvas(points, Modifier.fillMaxWidth().aspectRatio(1.3f), segments)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.hasDistance) StatTile("Distance", Fmt.km(r.correctedDistanceM), "km", Modifier.weight(1f))
            StatTile("Time", Fmt.duration(r.elapsedMs), "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val avg = if (r.movingMs > 0) r.correctedDistanceM / (r.movingMs / 1000.0) else null
            when (t) {
                ActivityType.CYCLING -> StatTile("Avg speed", Fmt.speedKmh(avg), "km/h", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
                ActivityType.EXERCISE -> Unit
                else -> StatTile("Steps", Fmt.steps(r.steps), "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
            }
            StatTile("Calories (est.)", Fmt.kcal(r.kcal), "kcal", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
        }
        AppCard {
            if (t.usesGps) {
                KeyValue("Raw GPS distance", "${Fmt.km3(r.rawDistanceM)} km")
                KeyValue("Calibration factor", if (r.factor == 1.0) "none (1.0000)" else Fmt.factor(r.factor))
                KeyValue("Displayed distance", "${Fmt.km3(r.correctedDistanceM)} km")
                KeyValue("Moving time", Fmt.duration(r.movingMs))
                if (t == ActivityType.CYCLING) KeyValue("Top speed", "${Fmt.speedKmh(r.topSpeedMps)} km/h")
                else KeyValue("Average pace", "${Fmt.pace(if (r.movingMs > 0) r.correctedDistanceM / (r.movingMs / 1000.0) else null)} /km")
                KeyValue("GPS fixes / avg accuracy", "${r.fixCount} / " + (r.meanAccuracyM?.let { String.format(java.util.Locale.US, "±%.1f m", it) } ?: "–"))
                KeyValue("Route points stored", "${points.size}")
            }
            if (t.countsSteps) {
                KeyValue("Steps", Fmt.steps(r.steps))
                if (r.elapsedMs > 60_000) KeyValue("Average cadence", "${(r.steps / (r.elapsedMs / 60_000.0)).toInt()} steps/min")
            }
            if (t.distanceFromSteps) KeyValue("Distance method", "steps × step length (no GPS indoors)")
            if (t == ActivityType.EXERCISE) KeyValue("Calorie method", "MET ${Calories.EXERCISE_MET} × weight × time")
        }
        Spacer(Modifier.height(16.dp))
    }
}
