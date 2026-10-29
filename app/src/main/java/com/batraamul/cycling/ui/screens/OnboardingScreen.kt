package com.batraamul.cycling.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.BatteryCard
import com.batraamul.cycling.ui.PermissionsCard
import com.batraamul.cycling.ui.components.ActionButton
import com.batraamul.cycling.ui.components.AppCard
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batraamul.cycling.ui.components.Banner
import com.batraamul.cycling.ui.has
import com.batraamul.cycling.ui.rememberResumeTick
import com.batraamul.cycling.ui.theme.LocalThemeSpec
import com.batraamul.cycling.ui.theme.SceneArtwork

@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var height by rememberSaveable { mutableStateOf("170") }
    var weight by rememberSaveable { mutableStateOf("70") }
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // After "Restore from backup", show the restored height/weight instead of the defaults.
    LaunchedEffect(settings?.heightCm, settings?.weightKg) {
        settings?.let { height = it.heightCm.toString(); weight = it.weightKg.toString().removeSuffix(".0") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            SceneArtwork(LocalThemeSpec.current, Modifier.fillMaxSize())
            // Dark fade at the bottom so the white text stays readable on every theme.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Text("Step ${page + 1} of 4", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                0 -> {
                    Text("Welcome", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "This app works like a quiet fitness band in your pocket:\n\n" +
                            "• Walking is counted automatically, all day, even with the screen off.\n" +
                            "• Cycling, running, jogging, treadmill and exercise sessions are recorded when you start them. During a session, daily step counting pauses so the same movement is never counted twice.\n" +
                            "• Data stays on this phone. You can keep a backup in your own Google Drive.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("Reinstalling after a phone reset?", style = MaterialTheme.typography.titleMedium)
                    BackupCard(vm, compact = true)
                }
                1 -> {
                    Text("About you", style = MaterialTheme.typography.headlineMedium)
                    Text("Height gives a first estimate of your step length (you can measure it precisely later in the Calibration Centre). Weight is used for calorie estimates.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppCard {
                        OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, label = { Text("Height (cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Weight (kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                2 -> {
                    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
                    PermissionsCard()
                }
                else -> {
                    Text("Keep tracking alive", style = MaterialTheme.typography.headlineMedium)
                    BatteryCard()
                    @Suppress("UNUSED_EXPRESSION") tick
                    if (!ctx.has(Manifest.permission.ACTIVITY_RECOGNITION)) {
                        Banner("Physical-activity permission is not granted yet, so steps cannot be counted. You can still finish and grant it later.")
                    }
                }
            }
            val h = height.toIntOrNull()
            val w = weight.toDoubleOrNull()
            val profileValid = h != null && h in 100..230 && w != null && w in 25.0..250.0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Back") } else Spacer(Modifier)
                ActionButton(
                    if (page < 3) "Continue" else "Start tracking",
                    enabled = page != 1 || profileValid,
                    onClick = {
                        if (page < 3) page++ else { vm.completeOnboarding(h ?: 170, w ?: 70.0); onDone() }
                    },
                    height = 56.dp,
                )
            }
            if (page == 1 && !profileValid) Text("Enter a height of 100–230 cm and a weight of 25–250 kg.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
