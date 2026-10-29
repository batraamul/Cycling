package com.batraamul.cycling.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.BarChart
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.SectionTitle
import com.batraamul.cycling.ui.components.StatTile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun AnalyticsScreen(vm: AppViewModel) {
    var weeksBack by rememberSaveable { mutableLongStateOf(0L) }
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeksBack)
    val sunday = monday.plusDays(6)
    // Each week is one SQL query aggregated per day — fast even with years of history.
    val weekFlow = remember(monday) { vm.repo.period(monday, sunday) }
    val week by weekFlow.collectAsStateWithLifecycle(null)
    val allTime by vm.repo.allTime.collectAsStateWithLifecycle(null)
    val locale = LocalConfiguration.current.locales[0]   // observable: recomposes if the user changes language
    val fmt = DateTimeFormatter.ofPattern("d MMM", locale)
    val labels = (0..6).map { monday.plusDays(it.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale) }
    val todayIndex = if (weeksBack == 0L) (today.dayOfWeek.value - 1) else -1

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analysis", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { weeksBack++ }) { Text("‹ Previous") }
            Text(if (weeksBack == 0L) "This week" else "${monday.format(fmt)} – ${sunday.format(fmt)}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { weeksBack-- }, enabled = weeksBack > 0) { Text("Next ›") }
        }
        val w = week
        if (w == null) { Text("Loading…"); return@Column }

        AppCard {
            Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            BarChart(w.days.map { it.steps.toDouble() }, labels, highlightIndex = todayIndex,
                valueFormatter = { if (it >= 1000) String.format(Locale.US, "%.1fk", it / 1000) else it.toLong().toString() })
        }
        AppCard {
            Text("Cycling distance (km)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            BarChart(w.days.map { it.cycleDistanceM / 1000 }, labels, color = MaterialTheme.colorScheme.secondary, highlightIndex = todayIndex,
                valueFormatter = { String.format(Locale.US, "%.1f", it) })
        }
        if (w.runSessions > 0) AppCard {
            Text("Running, jogging & treadmill (km)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            BarChart(w.days.map { it.runDistanceM / 1000 }, labels, color = MaterialTheme.colorScheme.tertiary, highlightIndex = todayIndex,
                valueFormatter = { String.format(Locale.US, "%.1f", it) })
        }
        SectionTitle("Week totals")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Steps", Fmt.steps(w.steps), "", Modifier.weight(1f))
            StatTile("Walking", Fmt.km(w.walkDistanceM, 1), "km", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Cycling", Fmt.km(w.cycleDistanceM, 1), "km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
            StatTile("Rides", "${w.rides}", "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Running", Fmt.km(w.runDistanceM, 1), "km", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
            StatTile("Runs / treadmill", "${w.runSessions}", "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Exercise time", Fmt.durationShort(w.exerciseMs), "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.primary)
            StatTile("Ride time", Fmt.durationShort(w.cycleElapsedMs), "", Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
        }
        StatTile("Calories (est.)", Fmt.kcal(w.kcal), "kcal", Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.tertiary)
        AppCard {
            val activeDays = w.days.count { it.steps > 0 || it.activities > 0 }
            KeyValue("Daily average steps", Fmt.steps(if (activeDays == 0) 0 else w.steps / activeDays))
            KeyValue("Active days", "$activeDays of 7")
            KeyValue("Total active distance", "${Fmt.km(w.walkDistanceM + w.cycleDistanceM + w.runDistanceM, 1)} km")
            Text("Walking and each activity are recorded separately: daily walking pauses during any activity, so no movement is counted twice. " +
                "Step totals include steps from running and treadmill sessions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        allTime?.let { a ->
            SectionTitle("All time")
            AppCard {
                KeyValue("Steps", Fmt.steps(a.steps))
                KeyValue("Walking distance", "${Fmt.km(a.walkDistanceM, 1)} km")
                KeyValue("Cycling distance", "${Fmt.km(a.cycleDistanceM, 1)} km")
                KeyValue("Rides", "${a.rides}")
                KeyValue("Time riding", Fmt.durationShort(a.cycleElapsedMs))
                KeyValue("Running / jogging / treadmill", "${Fmt.km(a.runDistanceM, 1)} km in ${a.runSessions} sessions")
                KeyValue("Exercise", "${Fmt.durationShort(a.exerciseMs)} in ${a.exerciseSessions} sessions")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
