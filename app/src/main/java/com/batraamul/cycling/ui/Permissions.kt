package com.batraamul.cycling.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.batraamul.cycling.ui.components.AppCard

fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.notificationsAllowed(): Boolean =
    Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)

fun Context.ignoringBatteryOptimisations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Opens the system dialog "Let app always run in background?" */
@SuppressLint("BatteryLife") // a continuous fitness tracker is a legitimate use; installed outside the Play Store
fun Context.requestUnrestrictedBattery() {
    try {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    } catch (e: Exception) { openAppSettings() }
}

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.openLocationSettings() {
    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Re-checks permissions whenever the screen resumes (e.g. after returning from system Settings). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    return tick
}

@Composable
fun PermissionRow(title: String, why: String, granted: Boolean, required: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title + if (required) "" else "  (optional now)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(4.dp))
        if (granted) Text("✓ Allowed", color = Color(0xFF2E9E5B), style = MaterialTheme.typography.labelLarge)
        else Button(onClick = onRequest) { Text("Allow") }
    }
}

/** All runtime permissions the app uses, with the reason for each. Shared by onboarding and Settings. */
@Composable
fun PermissionsCard() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    @Suppress("UNUSED_EXPRESSION") tick
    AppCard {
        Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PermissionRow("Physical activity", "Counts your steps in the background. Required for walking tracking.",
            ctx.has(Manifest.permission.ACTIVITY_RECOGNITION), required = true) {
            launcher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("Notifications", "Shows the small tracking notification Android requires for background tracking.",
                ctx.notificationsAllowed(), required = true) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        PermissionRow("Precise location", "Used only while a ride is being recorded. Choose “While using the app”. Never used for walking.",
            ctx.has(Manifest.permission.ACCESS_FINE_LOCATION), required = false) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        Text("If a button does nothing, Android has stopped asking: open App settings › Permissions instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { ctx.openAppSettings() }) { Text("Open app settings") }
    }
}

/** OxygenOS kills background apps aggressively; this explains what to change and why. */
@Composable
fun BatteryCard() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    @Suppress("UNUSED_EXPRESSION") tick
    val unrestricted = ctx.ignoringBatteryOptimisations()
    AppCard {
        Text("Battery settings for OnePlus / OxygenOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "OxygenOS may stop background apps to save power, which would pause step counting. " +
                "Step counting itself is light: with the phone's hardware step counter it uses very little battery.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "1. Tap “Allow unrestricted battery” below and confirm.\n" +
                "2. In App settings › Battery usage, enable “Allow background activity” (wording varies by OxygenOS version).\n" +
                "3. Optional: in Recent apps, lock the Cycling card so “Clear all” does not close it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unrestricted) Text("✓ Battery: unrestricted", color = Color(0xFF2E9E5B), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 10.dp))
            else Button(onClick = { ctx.requestUnrestrictedBattery() }) { Text("Allow unrestricted battery") }
            OutlinedButton(onClick = { ctx.openAppSettings() }) { Text("App settings") }
        }
    }
}
