package de.lukasrunge.verweil.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.GeofenceExit
import de.lukasrunge.verweil.hasPermission
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

/**
 * A geofence of radius R_exit around the stay anchor. Leaving it wakes the engine,
 * so location can stay cheap while staying.
 */
class StayGeofence(private val context: Context, private val radiusM: Float) {
    private val client = LocationServices.getGeofencingClient(context)

    private val intent by lazy {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            // Play Services fills in the event, so the intent must be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Moves the geofence to [anchor], or removes it for null. Returns whether a geofence is active. */
    @SuppressLint("MissingPermission") // Checked right before.
    suspend fun moveTo(anchor: GeoPoint?): Boolean = try {
        client.removeGeofences(intent).await()
        // Geofences need "Allow all the time" since Android 10.
        if (anchor == null || !context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            false
        } else {
            val fence = Geofence.Builder()
                .setRequestId(REQUEST_ID)
                .setCircularRegion(anchor.lat, anchor.lon, radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
            val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
            client.addGeofences(request, intent).await()
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // For example with location turned off. Regular fixes still notice the departure, only later.
        Log.w(TAG, "Geofence not available", e)
        false
    }

    private companion object {
        const val TAG = "StayGeofence"
        const val REQUEST_ID = "stay"
        const val RESPONSIVENESS_MS = 60_000
    }
}

/** Receives geofence exits from Play Services and hands them to the tracking service. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val app = context.applicationContext as VerweilApp
        app.sensorEvents.tryEmit(GeofenceExit(System.currentTimeMillis()))
    }
}
