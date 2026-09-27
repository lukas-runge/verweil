package de.lukasrunge.verweil.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange

/** Receives activity transitions from Play Services and hands them to the tracking service. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as VerweilApp

        // Transition times are elapsed-realtime based; convert them to wall-clock time.
        val nowMs = System.currentTimeMillis()
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()

        result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .forEach { event ->
                val timeMs = nowMs - (nowElapsedNs - event.elapsedRealTimeNanos) / 1_000_000
                app.sensorEvents.tryEmit(ActivityChange(timeMs, event.activityType.toActivity()))
            }
    }

    companion object {
        val trackedActivities = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
    }
}

private fun Int.toActivity(): Activity = when (this) {
    DetectedActivity.STILL -> Activity.STILL
    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Activity.WALKING
    DetectedActivity.RUNNING -> Activity.RUNNING
    DetectedActivity.ON_BICYCLE -> Activity.CYCLING
    DetectedActivity.IN_VEHICLE -> Activity.VEHICLE
    else -> Activity.UNKNOWN
}
