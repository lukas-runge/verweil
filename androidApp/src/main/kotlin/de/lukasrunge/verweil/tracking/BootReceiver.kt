package de.lukasrunge.verweil.tracking

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.hasPermission
import kotlinx.coroutines.launch

/** Resumes tracking after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as VerweilApp
        val pending = goAsync()
        app.scope.launch {
            try {
                val settings = app.settings.current()
                // Starting location tracking from the background needs "Allow all the time".
                if (settings.trackingEnabled && settings.isConfigured &&
                    context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ) {
                    TrackingService.start(context)
                }
            } catch (e: IllegalStateException) {
                // The system refused the start; the user can still start tracking from the app.
                Log.w("BootReceiver", "Could not resume tracking", e)
            } finally {
                pending.finish()
            }
        }
    }
}
