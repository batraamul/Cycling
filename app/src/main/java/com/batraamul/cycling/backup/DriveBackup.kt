package com.batraamul.cycling.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.batraamul.cycling.CyclingApp
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/*
 * HOW THE GOOGLE DRIVE BACKUP WORKS (and why it needs no internet permission)
 *
 * Android's Storage Access Framework lets the user pick where a file goes, using the system file
 * picker. Google Drive plugs into that picker as a "documents provider". When the user chooses a
 * Drive folder, this app receives a content:// URI. Writing to that URI hands the bytes to the Drive
 * app, and the DRIVE app uploads them when the phone is online. So:
 *   - this app never talks to the network (it still has no INTERNET permission);
 *   - no Google sign-in, API key or Cloud project is needed inside this app;
 *   - the backup survives a factory reset, because it lives in the user's Google account.
 *
 * "Persistable URI permission" lets the app write to the same file again later (daily backups)
 * without asking each time. The user can revoke it by choosing another file.
 */

/** Small device-local settings for backup. Deliberately NOT in the database, so they are never in a backup. */
class BackupPrefs(context: Context) {
    private val p = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    var uri: Uri?
        get() = p.getString("uri", null)?.let(Uri::parse)
        set(v) = p.edit { putString("uri", v?.toString()) }
    var fileLabel: String?
        get() = p.getString("label", null)
        set(v) = p.edit { putString("label", v) }
    var auto: Boolean
        get() = p.getBoolean("auto", false)
        set(v) = p.edit { putBoolean("auto", v) }
    var lastSuccessMs: Long
        get() = p.getLong("lastOk", 0)
        set(v) = p.edit { putLong("lastOk", v) }
    var lastError: String?
        get() = p.getString("lastError", null)
        set(v) = p.edit { putString("lastError", v) }
    var lastSizeBytes: Long
        get() = p.getLong("lastSize", 0)
        set(v) = p.edit { putLong("lastSize", v) }
}

object DriveBackup {
    const val SUGGESTED_NAME = "Cycling-backup.json"
    private const val PERIODIC = "auto-backup-daily"
    private const val SOON = "auto-backup-soon"

    sealed interface Outcome {
        data class Saved(val bytes: Long) : Outcome
        data object SkippedEmpty : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Remembers the file the user created/picked and keeps write access to it across reboots. */
    fun rememberFile(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Some providers do not offer lasting access; manual backups still work, daily ones may not.
        }
        val prefs = BackupPrefs(context)
        prefs.uri = uri
        prefs.fileLabel = describe(context, uri)
        prefs.lastError = null
    }

    /** "Cycling-backup.json · Google Drive" — shown in Settings so the user knows where backups go. */
    fun describe(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: SUGGESTED_NAME
        val where = when {
            uri.authority?.contains("com.google.android.apps.docs") == true -> "Google Drive"
            uri.authority?.contains("externalstorage") == true || uri.authority?.contains("media") == true -> "phone storage (lost on factory reset!)"
            else -> uri.authority ?: "unknown location"
        }
        return "$name · $where"
    }

    fun isOnDrive(uri: Uri?) = uri?.authority?.contains("com.google.android.apps.docs") == true

    /**
     * Writes a full backup to the remembered file.
     * @param manual true when the user pressed "Back up now" (then an empty backup is allowed).
     */
    suspend fun backupNow(context: Context, manual: Boolean): Outcome {
        val prefs = BackupPrefs(context)
        val uri = prefs.uri ?: return Outcome.Failed("No backup file chosen yet.")
        val manager = (context.applicationContext as CyclingApp).container.backup
        // Safety: after a reinstall the database is empty; an automatic backup must not overwrite the
        // good copy in Drive with an empty one before the user has had a chance to restore it.
        if (!manual && !manager.hasData()) return Outcome.SkippedEmpty
        return try {
            val out = openForOverwrite(context, uri) ?: throw IllegalStateException("The backup location did not open for writing.")
            val counter = CountingStream(out)
            OutputStreamWriter(counter, Charsets.UTF_8).buffered(64 * 1024).use { manager.export(it) }
            prefs.lastSuccessMs = System.currentTimeMillis()
            prefs.lastSizeBytes = counter.count
            prefs.lastError = null
            Outcome.Saved(counter.count)
        } catch (e: Exception) {
            val msg = when (e) {
                is SecurityException -> "Permission to write the backup file was lost. Choose the backup file again."
                is java.io.FileNotFoundException -> "The backup file no longer exists (deleted in Drive?). Choose a backup file again."
                else -> e.message ?: e.javaClass.simpleName
            }
            prefs.lastError = msg
            Outcome.Failed(msg)
        }
    }

    /** "wt" = write + truncate (replace the whole file). Some providers only accept "w", which also replaces it. */
    private fun openForOverwrite(context: Context, uri: Uri) =
        try { context.contentResolver.openOutputStream(uri, "wt") } catch (e: IllegalArgumentException) { null }
            ?: context.contentResolver.openOutputStream(uri, "w")

    // ---------------- scheduling (WorkManager) ----------------

    /**
     * Daily backup via WorkManager: Android runs it at a battery-friendly moment (batched with other
     * apps' work, only when the battery is not low) and remembers the schedule across reboots.
     */
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(SOON); return }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** One extra backup shortly after an activity is finished, so a new ride is safe the same day. */
    fun backupSoon(context: Context) {
        if (!BackupPrefs(context).auto) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(2, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, req)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BackupPrefs(applicationContext).auto) return Result.success()
        return when (DriveBackup.backupNow(applicationContext, manual = false)) {
            is DriveBackup.Outcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}

private class CountingStream(private val inner: java.io.OutputStream) : java.io.FilterOutputStream(inner) {
    var count = 0L
    override fun write(b: Int) { inner.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
}
