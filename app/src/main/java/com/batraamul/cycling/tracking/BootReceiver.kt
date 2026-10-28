package com.batraamul.cycling.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batraamul.cycling.CyclingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts walking tracking after the phone reboots or the app is updated.
 * A ride in progress is NOT restarted automatically (Android forbids starting GPS tracking from
 * the background); the engine marks it "interrupted" and notifies the user instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext as CyclingApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.container.db.settings().getOrDefault().onboardingDone) {
                    app.container.controller.startServiceFromBackground()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
