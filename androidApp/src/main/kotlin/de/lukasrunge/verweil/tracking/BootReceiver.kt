package de.lukasrunge.verweil.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.lukasrunge.verweil.VerweilApp
import kotlinx.coroutines.launch

/** Resumes tracking after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as VerweilApp
        val pending = goAsync()
        app.scope.launch {
            try {
                TrackingWatchdog.resumeIfStopped(context)
            } finally {
                pending.finish()
            }
        }
    }
}
