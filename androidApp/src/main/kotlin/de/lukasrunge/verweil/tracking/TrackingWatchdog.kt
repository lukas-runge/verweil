package de.lukasrunge.verweil.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lukasrunge.verweil.Notifications
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.access
import java.util.concurrent.TimeUnit

/**
 * Brings tracking back when Android or the manufacturer stopped it, and tells the user when that is
 * impossible, e.g. after they took away location access. Runs every 15 minutes while tracking is on.
 */
class TrackingWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        resumeIfStopped(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "tracking-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingWatchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Restarts tracking if the user wants it on and it is not running; after a reboot, an app update,
         * or the process being killed. Starting from the background needs "Allow all the time".
         */
        suspend fun resumeIfStopped(context: Context) {
            val app = context.applicationContext as VerweilApp
            val settings = app.settings.current()
            if (!settings.trackingEnabled || !settings.isConfigured || app.status.value.running) return
            val access = context.access()
            val reason = when {
                !access.preciseLocation -> context.getString(R.string.interrupted_no_location)
                !access.backgroundLocation -> context.getString(R.string.interrupted_no_background)
                TrackingService.start(context) -> return
                else -> context.getString(R.string.interrupted_start_refused)
            }
            // Once per interruption: the notification should not come back every 15 minutes after a swipe.
            if (!app.settings.interruptionNotified()) {
                Notifications.trackingInterrupted(context, reason)
                app.settings.setInterruptionNotified(true)
            }
        }
    }
}
