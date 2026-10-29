package com.batraamul.cycling.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batraamul.cycling.backup.DriveBackup
import com.batraamul.cycling.data.BackupCodec
import com.batraamul.cycling.ui.AppViewModel
import com.batraamul.cycling.ui.Fmt
import com.batraamul.cycling.ui.components.ActionButton
import com.batraamul.cycling.ui.components.AppCard
import com.batraamul.cycling.ui.components.Banner
import com.batraamul.cycling.ui.components.KeyValue
import com.batraamul.cycling.ui.components.OutlineActionButton

/**
 * Backup & restore card (Settings). Uses the system file picker, where the user chooses Google Drive:
 * see backup/DriveBackup.kt for why this needs no internet permission or sign-in in this app.
 */
@Composable
fun BackupCard(vm: AppViewModel, compact: Boolean = false) {
    val state by vm.backup.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshBackupState() }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME)) { uri ->
        if (uri != null) vm.backupFileChosen(uri)
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openRestoreFile(uri)
    }

    AppCard {
        if (!compact) {
            Text("Keep a copy of all your steps, activities, routes and calibrations as a JSON file in your Google Drive. " +
                "If the phone is reset or replaced, install the app and restore it.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
        }
        if (!compact) {
            if (state.fileLabel == null) {
                ActionButton("Set up Google Drive backup", onClick = { createFile.launch(DriveBackup.SUGGESTED_NAME) }, Modifier.fillMaxWidth(), icon = "☁")
                Text("In the file picker, tap ☰ (top-left) → Drive, choose a folder and tap Save.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                KeyValue("Backup file", state.fileLabel!!)
                KeyValue("Last backup", if (state.lastSuccessMs == 0L) "never" else "${Fmt.dateTime(state.lastSuccessMs)} · ${Fmt.bytes(state.lastSizeBytes)}")
                if (!state.onDrive) Banner("This file is not in Google Drive, so it would be lost if the phone is reset. Choose a Drive folder instead.")
                state.lastError?.let { Banner("Last backup failed: $it") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic daily backup", style = MaterialTheme.typography.titleSmall)
                        Text("Also runs a few minutes after each finished activity.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.auto, onCheckedChange = { vm.setAutoBackup(it) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(if (state.busy) "Saving…" else "Back up now", onClick = { vm.backupNow() }, Modifier.weight(1f),
                        icon = "⬆", enabled = !state.busy, height = 56.dp)
                    OutlineActionButton("Change file", onClick = { createFile.launch(DriveBackup.SUGGESTED_NAME) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlineActionButton("Restore from a backup file", onClick = { pickRestore.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
            Modifier.fillMaxWidth(), icon = "⬇")
        Text("Restoring merges the backup with what is on this phone: nothing here is deleted, and restoring the same file twice changes nothing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    RestoreDialog(vm)
}

@Composable
private fun RestoreDialog(vm: AppViewModel) {
    val pending by vm.restorePreview.collectAsStateWithLifecycle()
    val p = pending?.second ?: return
    AlertDialog(
        onDismissRequest = { vm.cancelRestore() },
        title = { Text("Restore this backup?") },
        text = {
            Column {
                KeyValue("Made on", "${Fmt.dateTime(p.createdAtMs)} (app ${p.appVersion})")
                KeyValue("Phone", p.device)
                KeyValue("Days of walking", "${p.days}" + if (p.firstDay != null) " (${p.firstDay} → ${p.lastDay})" else "")
                KeyValue("Activities", "${p.sessions} (${p.routePoints} route points)")
                KeyValue("Steps in backup", Fmt.steps(p.steps))
                KeyValue("Calibrations", "${p.calibrations}")
                Spacer(Modifier.height(8.dp))
                Text("Days and activities missing on this phone are added. Where a day exists in both, the higher step count is kept. " +
                    "Nothing on this phone is deleted.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmRestore() }) { Text("Restore", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("Cancel") } },
    )
}
