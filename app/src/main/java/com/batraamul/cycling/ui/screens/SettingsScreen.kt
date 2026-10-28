package com.batraamul.cycling.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.batraamul.cycling.BuildConfig
import com.batraamul.cycling.data.StepSourceSetting
import com.batraamul.cycling.tracking.AndroidStepSensor
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.BatteryCard
import com.batraamul.cycling.ui.PermissionsCard
import com.batraamul.cycling.ui.Routes
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.SectionTitle
import com.batraamul.cycling.ui.theme.AppThemes
import com.batraamul.cycling.ui.theme.SceneArtwork

@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val s = settings ?: return
    val sensorInfo = remember { AndroidStepSensor.describeSensors(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Theme")
        // Eight themes, one shared app: only colours and artwork change. Artwork is drawn on demand.
        AppThemes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val selected = t.id == s.themeId
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                            .clickable { vm.updateSettings { it.copy(themeId = t.id) } },
                    ) {
                        Box(Modifier.fillMaxWidth().height(78.dp)) { SceneArtwork(t, Modifier.fillMaxSize()) }
                        Column(Modifier.padding(10.dp)) {
                            Text(t.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(if (selected) "In use" else t.tagline, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }

        SectionTitle("Walking tracking")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Count steps in the background", style = MaterialTheme.typography.titleSmall)
                    Text("On by default. Paused automatically during rides.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = live.mode.walkingEnabled, onCheckedChange = { vm.setWalkingEnabled(it) })
            }
            Spacer(Modifier.height(10.dp))
            Text("Step sensor", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setStepSource(StepSourceSetting.AUTO) }) {
                RadioButton(s.stepSource == StepSourceSetting.AUTO, { vm.setStepSource(StepSourceSetting.AUTO) })
                Text("Automatic — hardware step counter if available (recommended)")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setStepSource(StepSourceSetting.ACCELEROMETER) }) {
                RadioButton(s.stepSource == StepSourceSetting.ACCELEROMETER, { vm.setStepSource(StepSourceSetting.ACCELEROMETER) })
                Text("Accelerometer only — for testing the fallback algorithm")
            }
            Spacer(Modifier.height(6.dp))
            Text("Sensors on this phone", style = MaterialTheme.typography.labelLarge)
            Text(sensorInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Profile")
        ProfileCard(s.heightCm, s.weightKg, s.dailyStepGoal) { h, w, g -> vm.updateSettings { it.copy(heightCm = h, weightKg = w, dailyStepGoal = g) } }

        SectionTitle("Calibration")
        AppCard {
            Text("Measure your step length and calibrate GPS distance on a known 1 km course.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { nav.navigate(Routes.CALIBRATION) }, modifier = Modifier.fillMaxWidth()) { Text("Open Calibration Centre") }
        }

        SectionTitle("Permissions & battery")
        PermissionsCard()
        BatteryCard()

        SectionTitle("About")
        AppCard {
            KeyValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Data", "Stored only on this phone")
            Text("Step counts during a ride are deliberately not recorded: if you dismount and walk while a ride is active, " +
                "those steps are excluded. Calorie values are estimates.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProfileCard(heightCm: Int, weightKg: Double, goal: Int, onSave: (Int, Double, Int) -> Unit) {
    var h by rememberSaveable(heightCm) { mutableStateOf(heightCm.toString()) }
    var w by rememberSaveable(weightKg) { mutableStateOf(weightKg.toString()) }
    var g by rememberSaveable(goal) { mutableStateOf(goal.toString()) }
    val hv = h.toIntOrNull(); val wv = w.toDoubleOrNull(); val gv = g.toIntOrNull()
    val valid = hv != null && hv in 100..230 && wv != null && wv in 25.0..250.0 && gv != null && gv in 500..100_000
    AppCard {
        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(3) }, label = { Text("Height (cm)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(w, { w = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Weight (kg)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(g, { g = it.filter(Char::isDigit).take(6) }, label = { Text("Daily step goal") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Text("Changing height does not change a step length you have measured.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(enabled = valid, onClick = { onSave(hv!!, wv!!, gv!!) }) { Text("Save profile") }
        if (!valid) Text("Check the values (height 100–230 cm, weight 25–250 kg, goal 500–100 000).", color = Color(0xFFD9534F),
            style = MaterialTheme.typography.bodySmall)
    }
}
