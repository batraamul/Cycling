package com.batraamul.cycling.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.batraamul.cycling.core.Confidence
import com.batraamul.cycling.core.GpsCalibrationEvaluation
import com.batraamul.cycling.core.GpsQuality
import com.batraamul.cycling.core.StepCalibrationMath
import com.batraamul.cycling.core.TrackingMode
import com.batraamul.cycling.data.AppRepository
import com.batraamul.cycling.data.Ride
import com.batraamul.cycling.data.StepLengthSource
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.Routes
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.Banner
import com.batraamul.cycling.ui.components.ConfidenceChip
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.SectionTitle
import com.batraamul.cycling.ui.has
import java.util.Locale

@Composable
private fun ScreenColumn(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

private fun methodLabel(source: String) = when (source) {
    StepLengthSource.SINGLE_STEP -> "Measured single steps"
    StepLengthSource.COURSE -> "Measured course ÷ steps"
    StepLengthSource.MANUAL -> "Entered manually"
    else -> "Estimated from height (not yet calibrated)"
}

private fun confidenceOf(s: String?) = s?.let { runCatching { Confidence.valueOf(it) }.getOrNull() }

// =========================================================================================
// Calibration Centre (dashboard)
// =========================================================================================

@Composable
fun CalibrationCentreScreen(vm: AppViewModel, nav: NavController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gps by vm.gpsStatus.collectAsStateWithLifecycle()
    val stepHistory by vm.repo.stepCalibrationHistory.collectAsStateWithLifecycle(emptyList())
    val gpsHistory by vm.repo.gpsCalibrationHistory.collectAsStateWithLifecycle(emptyList())
    var confirmReset by remember { mutableStateOf(false) }
    val s = settings ?: return

    ScreenColumn("Calibration Centre", "Your measurements make distances accurate. All calibration data stays on this phone.") {
        AppCard {
            Text("Walking — step length", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            KeyValue("Walking step", Fmt.stepCm(s.walkStepM))
            KeyValue("Method", methodLabel(s.walkStepSource))
            KeyValue("Calibrated", s.walkStepCalibratedAtMs?.let { Fmt.date(it) } ?: "Never")
            KeyValue("Running step", s.runStepM?.let { Fmt.stepCm(it) + " (used above 140 steps/min)" } ?: "Not set — walking value used")
            KeyValue("Distance formula", "steps × ${Fmt.stepCm(s.walkStepM)} ÷ 1000 = km")
            Spacer(Modifier.height(6.dp))
            ConfidenceChip(if (s.walkStepSource == StepLengthSource.HEIGHT_ESTIMATE) Confidence.NONE else confidenceOf(s.walkStepConfidence))
            Spacer(Modifier.height(10.dp))
            Button(onClick = { nav.navigate(Routes.CAL_STEP) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.walkStepCalibratedAtMs == null) "Calibrate step length" else "Recalibrate step length")
            }
        }

        AppCard {
            Text("Cycling — GPS distance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val g = gps
            KeyValue("Status", if (s.gpsFactor == 1.0) "Default GPS calculation (no correction)" else "Correction applied")
            KeyValue("Factor k", "${Fmt.factor(s.gpsFactor)} (${Fmt.pct(s.gpsFactor)})")
            KeyValue("Calibrations in use", "${g?.appliedCount ?: 0}" + (g?.spread?.let { " · spread ${String.format(Locale.US, "%.1f", it * 100)} %" } ?: ""))
            KeyValue("Last calibrated", s.gpsCalibratedAtMs?.let { Fmt.date(it) } ?: "Never")
            Spacer(Modifier.height(6.dp))
            ConfidenceChip(g?.confidence)
            Spacer(Modifier.height(6.dp))
            Text("Raw GPS distance is always stored with every ride, so a correction can be reviewed or reset at any time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { nav.navigate(Routes.CAL_GPS) }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate Cycling Distance") }
            OutlinedButton(onClick = { confirmReset = true }, enabled = s.gpsFactor != 1.0 || (g?.appliedCount ?: 0) > 0,
                modifier = Modifier.fillMaxWidth()) { Text("Reset to default GPS calculation") }
        }

        if (stepHistory.isNotEmpty()) {
            SectionTitle("Recent step calibrations")
            AppCard {
                stepHistory.take(5).forEach {
                    KeyValue("${Fmt.date(it.timeMs)} · ${if (it.kind == AppRepository.KIND_RUN) "Run" else "Walk"} · ${methodLabel(it.method)}", Fmt.stepCm(it.resultM))
                }
            }
        }
        if (gpsHistory.isNotEmpty()) {
            SectionTitle("Recent GPS calibrations")
            AppCard {
                gpsHistory.take(5).forEach {
                    KeyValue("${Fmt.date(it.timeMs)} · ${it.quality.lowercase().replaceFirstChar(Char::uppercase)}" + if (it.applied) " · in use" else "",
                        "${Fmt.km3(it.rawKm * 1000)} km → k ${Fmt.factor(it.factor)}")
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset GPS calculation?") },
            text = { Text("Future rides will use the raw GPS distance (factor 1.0000). Past rides keep the factor they were recorded with. Calibration history is kept.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetGps() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

// =========================================================================================
// Step-length wizard
// =========================================================================================

@Composable
fun StepCalibrationScreen(vm: AppViewModel, onDone: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf(AppRepository.KIND_WALK) }

    ScreenColumn("Step length", "Step length depends on how fast and how you walk, so measure at your normal pace. You can recalibrate any time.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == AppRepository.KIND_WALK, { kind = AppRepository.KIND_WALK }, label = { Text("Walking") })
            FilterChip(kind == AppRepository.KIND_RUN, { kind = AppRepository.KIND_RUN }, label = { Text("Running (optional)") })
        }
        TabRow(selectedTabIndex = tab) {
            listOf("One step", "Measured course", "Enter value").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> SingleStepTab(vm, kind, onDone)
            1 -> CourseTab(vm, kind, onDone)
            else -> ManualTab(vm, kind, onDone)
        }
    }
}

private fun rangeFor(kind: String) = if (kind == AppRepository.KIND_RUN) StepCalibrationMath.RUN_RANGE_M else StepCalibrationMath.WALK_RANGE_M

@Composable
private fun SingleStepTab(vm: AppViewModel, kind: String, onDone: () -> Unit) {
    val measurements = remember { mutableStateListOf<Double>() }
    var input by rememberSaveable { mutableStateOf("") }
    var unitCm by rememberSaveable { mutableStateOf(true) }
    val range = rangeFor(kind)

    AppCard {
        Text("How to measure one step", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "One step = the distance from the heel of one foot to the heel of the other foot when you take a single normal step.\n\n" +
                "1. Stand with both heels on a line (a floor tile edge works well).\n" +
                "2. Walk a few steps first to get your natural rhythm, then stop mid-stride with one foot forward.\n" +
                "3. Measure heel to heel with a tape measure.\n" +
                "4. Repeat 3–5 times. The median is used, so one odd measurement won't spoil the result.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(unitCm, { unitCm = true }, label = { Text("cm") })
            FilterChip(!unitCm, { unitCm = false }, label = { Text("m") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                label = { Text("Measured step (${if (unitCm) "cm" else "m"})") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(4.dp))
            val value = input.replace(',', '.').toDoubleOrNull()?.let { if (unitCm) it / 100 else it }
            Button(enabled = value != null && value in range, onClick = { measurements += value!!; input = "" }) { Text("Add") }
        }
        Text("Accepted range: ${Fmt.stepCm(range.start)} – ${Fmt.stepCm(range.endInclusive)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        measurements.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.  ${Fmt.stepCm(m)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { measurements.removeAt(i) }) { Text("Remove") }
            }
        }
    }
    val summary = StepCalibrationMath.summarize(measurements.toList())
    if (summary != null) {
        AppCard {
            KeyValue("Measurements", "${summary.n}")
            KeyValue("Median / mean", "${Fmt.stepCm(summary.medianM)} / ${Fmt.stepCm(summary.meanM)}")
            KeyValue("Variation (CV)", String.format(Locale.US, "%.1f %%", summary.cv * 100))
            KeyValue("Value to save", Fmt.stepCm(summary.recommendedM))
            ConfidenceChip(summary.confidence)
            if (summary.n < 3) Text("Add at least 3 measurements for a reliable value.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.saveStepLength(kind, StepLengthSource.SINGLE_STEP, measurements.joinToString { Fmt.stepCm(it) }, summary.recommendedM, summary.confidence, onDone)
            }, modifier = Modifier.fillMaxWidth()) { Text("Save ${Fmt.stepCm(summary.recommendedM)} as ${if (kind == AppRepository.KIND_RUN) "running" else "walking"} step") }
        }
    }
}

@Composable
private fun CourseTab(vm: AppViewModel, kind: String, onDone: () -> Unit) {
    val live by vm.live.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    var lengthText by rememberSaveable { mutableStateOf("") }
    var stepsText by rememberSaveable { mutableStateOf("") }
    var countStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentTotal = (today?.steps ?: 0) + live.pendingSteps
    val walkingActive = live.mode.mode == TrackingMode.WALKING && live.engineRunning

    AppCard {
        Text("Multi-step course (more accurate)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Walk a course whose length you know (e.g. 20–50 m measured with a tape, or a running-track straight) at your normal pace, " +
                "and count your steps. Step length = course length ÷ steps. This averages out step-to-step variation, " +
                "unlike the single-step method.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    AppCard {
        OutlinedTextField(lengthText, { lengthText = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, label = { Text("Course length (m)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(stepsText, { stepsText = it.filter(Char::isDigit).take(5) }, label = { Text("Steps taken") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (walkingActive) {
            val start = countStart
            if (start == null) {
                OutlinedButton(onClick = { countStart = currentTotal }, modifier = Modifier.fillMaxWidth()) { Text("Let the app count my steps — Start") }
            } else {
                Text("Counting… ${currentTotal - start} steps so far", style = MaterialTheme.typography.bodyMedium)
                Text("At the end, stand still for ~30 s before pressing Stop: the step sensor reports in batches.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { stepsText = (currentTotal - start).toString(); countStart = null }, modifier = Modifier.fillMaxWidth()) { Text("Stop counting") }
            }
        } else {
            Text("Automatic counting needs walking tracking to be active; count the steps yourself instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val length = lengthText.toDoubleOrNull()
    val steps = stepsText.toIntOrNull()
    val result = if (length != null && steps != null) StepCalibrationMath.fromCourse(length, steps) else null
    if (result != null) {
        val ok = result in rangeFor(kind)
        val conf = StepCalibrationMath.courseConfidence(steps!!)
        AppCard {
            KeyValue("Step length", "${Fmt.stepCm(result)}  (${String.format(Locale.US, "%.1f", length)} m ÷ $steps)")
            ConfidenceChip(conf)
            if (!ok) Text("This is outside the plausible range — check the length and step count.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(enabled = ok, onClick = {
                vm.saveStepLength(kind, StepLengthSource.COURSE, "${length} m course, $steps steps", result, conf, onDone)
            }, modifier = Modifier.fillMaxWidth()) { Text("Save ${Fmt.stepCm(result)}") }
        }
    }
}

@Composable
private fun ManualTab(vm: AppViewModel, kind: String, onDone: () -> Unit) {
    var cm by rememberSaveable { mutableStateOf("") }
    val value = cm.replace(',', '.').toDoubleOrNull()?.div(100)
    val ok = value != null && value in rangeFor(kind)
    AppCard {
        Text("If you already know your step length (e.g. measured elsewhere), enter it directly.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(cm, { cm = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, label = { Text("Step length (cm)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(enabled = ok, onClick = { vm.saveStepLength(kind, StepLengthSource.MANUAL, "$cm cm entered", value!!, Confidence.MEDIUM, onDone) },
            modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
    if (kind == AppRepository.KIND_RUN) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        if (settings?.runStepM != null) {
            OutlinedButton(onClick = { vm.clearRunStep() }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove running step length (use walking value for all steps)")
            }
        }
    }
}

// =========================================================================================
// GPS 1 km calibration
// =========================================================================================

@Composable
fun GpsCalibrationIntroScreen(vm: AppViewModel, nav: NavController) {
    val ctx = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val start = { vm.startCycling(isCalibration = true) { nav.navigate(Routes.RIDE) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) start()
    }
    ScreenColumn("Calibrate Cycling Distance", "Compare the app's GPS distance with a route you know is exactly 1.000 km.") {
        AppCard {
            Text("Before you start", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "1. Find a measured 1.000 km course: a marked running track (2.5 laps of a 400 m track), kilometre stones on a road, " +
                    "or a route you measured precisely beforehand.\n" +
                    "2. Choose open sky: tall buildings and dense trees reflect or block GPS signals.\n" +
                    "3. Stand at the start for 20–30 s after pressing Start so the GPS settles.\n" +
                    "4. Ride the course at a steady, normal pace and press Stop exactly at the 1 km mark.\n" +
                    "5. Repeat 2–3 times on different days to confirm the result is consistent.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Banner("The correction is only applied if you confirm it and the GPS quality was good enough. Poor-quality traces are rejected automatically.", warning = false)
        if (live.mode.inSession) {
            Banner("A ride is already in progress. Finish or discard it first.")
            Button(onClick = { nav.navigate(Routes.RIDE) }, modifier = Modifier.fillMaxWidth()) { Text("Open current ride") }
        } else {
            Button(onClick = {
                if (ctx.has(Manifest.permission.ACCESS_FINE_LOCATION)) start()
                else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start calibration ride") }
        }
    }
}

@Composable
fun GpsCalibrationResultScreen(vm: AppViewModel, rideId: Long, onDone: () -> Unit) {
    var result by remember { mutableStateOf<Pair<Ride, GpsCalibrationEvaluation>?>(null) }
    var courseConfirmed by rememberSaveable { mutableStateOf(false) }
    var warningAck by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rideId) { result = vm.repo.evaluateCalibrationRide(rideId, referenceKm = 1.0) }
    val r = result
    ScreenColumn("Calibration result") {
        if (r == null) { Text("Loading…"); return@ScreenColumn }
        val (ride, e) = r
        val qColor = when (e.quality) { GpsQuality.GOOD -> Color(0xFF2E9E5B); GpsQuality.WARNING -> Color(0xFFE0A100); GpsQuality.REJECTED -> Color(0xFFD9534F) }
        AppCard {
            KeyValue("Known reference distance", "1.000 km")
            KeyValue("Raw GPS distance", "${Fmt.km3(ride.rawDistanceM)} km")
            KeyValue("Difference", String.format(Locale.US, "%+.0f m", ride.rawDistanceM - 1000))
            KeyValue("Candidate factor k = 1.000 ÷ ${Fmt.km3(ride.rawDistanceM)}", Fmt.factor(e.factor))
            KeyValue("Average GPS accuracy", ride.meanAccuracyM?.let { String.format(Locale.US, "±%.1f m", it) } ?: "–")
            KeyValue("Usable GPS fixes", "${ride.fixCount}")
            KeyValue("Duration", Fmt.duration(ride.elapsedMs))
            Spacer(Modifier.height(6.dp))
            Text("Quality: " + e.quality.name.lowercase().replaceFirstChar(Char::uppercase), color = qColor, fontWeight = FontWeight.SemiBold)
            e.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (e.correctionNegligible) Text("GPS was already within 1 % of the true distance — a correction is unnecessary, but recording it still helps confirm consistency.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(courseConfirmed, { courseConfirmed = it })
                Text("I confirm I rode exactly the 1.000 km reference course and stopped at the finish.", style = MaterialTheme.typography.bodyMedium)
            }
            if (e.quality == GpsQuality.WARNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(warningAck, { warningAck = it })
                    Text("I understand the warnings above and still want to apply this correction.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            val canApply = courseConfirmed && e.quality != GpsQuality.REJECTED && (e.quality != GpsQuality.WARNING || warningAck)
            Button(enabled = canApply, onClick = { vm.saveGpsCalibration(rideId, e, apply = true, onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save and apply k = ${Fmt.factor(e.factor)}")
            }
            OutlinedButton(onClick = { vm.saveGpsCalibration(rideId, e, apply = false, onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text("Record result without applying")
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Don't save this calibration") }
            Text("The calibration ride itself stays in your ride history (labelled “Calibration”).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
