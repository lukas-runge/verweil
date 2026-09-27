package de.lukasrunge.verweil.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.tracking.Tracker
import de.lukasrunge.verweil.hasPermission
import de.lukasrunge.verweil.ui.MainActivity
import de.lukasrunge.verweil.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How precisely and how often to ask for location. */
private enum class LocationProfile(val priority: Int, val intervalMs: Long, val minIntervalMs: Long) {
    /** Every second: the track smoother needs many fixes, and GNSS is running anyway. */
    MOVING(Priority.PRIORITY_HIGH_ACCURACY, 1_000, 1_000),

    /** Without a geofence, regular fixes are the only way to notice a departure without motion. */
    STAYING(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000, 60_000),

    /** The geofence and activity transitions wake the engine; fixes other apps request come along for free. */
    STAYING_GEOFENCED(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60_000, 60_000),
}

/**
 * Collects location fixes, activity transitions, Wi-Fi scans and geofence exits, runs them through the engine
 * and queues its decisions for upload. Runs as a foreground service of type location.
 */
class TrackingService : LifecycleService() {

    private val app get() = application as VerweilApp

    /** Single queue, so the engine sees events strictly one after another. Closing it ends the event loop. */
    private val events = Channel<SensorEvent>(Channel.UNLIMITED)

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var geofence: StayGeofence
    private var profile: LocationProfile? = null
    private var geofencedAnchor: GeoPoint? = null
    private var geofenceActive = false
    private var notifiedMode: Mode? = null
    private var started = false

    @Volatile
    private var wifi: WifiScanner? = null

    /** The user stopped tracking, as opposed to the system stopping the service. */
    @Volatile
    private var stopRequested = false

    @Volatile
    private var recordRawEvents = true

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { events.trySend(it.toFix()) }
        }
    }

    private val transitionsIntent by lazy {
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ActivityTransitionReceiver::class.java),
            // Play Services fills in the result, so the intent must be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        app.scope.launch { app.settings.setTrackingEnabled(true) }

        fused = LocationServices.getFusedLocationProviderClient(this)
        geofence = StayGeofence(this, EngineConfig().exitRadiusM.toFloat())
        requestActivityTransitions()

        lifecycleScope.launch { app.settings.values.collect { recordRawEvents = it.recordRawEvents } }
        lifecycleScope.launch { app.sensorEvents.collect { events.trySend(it) } }
        lifecycleScope.launch {
            // Lets engine timeouts fire even when no sensor reports anything.
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                events.trySend(Tick(System.currentTimeMillis()))
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Continues a stay that was going on when the app was killed.
            val tracker = Tracker(app.database)
            val scanner = WifiScanner(this@TrackingService, app.settings.wifiSalt()) { events.trySend(it) }
            withContext(Dispatchers.Main) {
                scanner.start()
                wifi = scanner
            }
            processEvents(tracker)
        }

        app.status.update { it.copy(running = true, mode = null) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // Checked right before removing the transition updates.
    override fun onDestroy() {
        if (started) {
            fused.removeLocationUpdates(locationCallback)
            if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionsIntent)
            }
            wifi?.stop()
        }
        events.close()
        app.status.update { it.copy(running = false) }
        super.onDestroy()
    }

    /** The event loop drains what is queued, closes an open stay and then stops the service. */
    private fun requestStop() {
        app.scope.launch { app.settings.setTrackingEnabled(false) }
        if (!started) {
            stopSelf()
            return
        }
        stopRequested = true
        events.close()
    }

    private suspend fun processEvents(tracker: Tracker) {
        val recorder = Recorder(this)
        adapt(tracker, System.currentTimeMillis())
        for (event in events) {
            if (recordRawEvents) recorder.write(event)
            if (tracker.process(event).isNotEmpty()) UploadWorker.enqueue(this)
            adapt(tracker, event.timeMs)
        }
        if (stopRequested) {
            if (tracker.finish().isNotEmpty()) UploadWorker.enqueue(this)
            geofence.moveTo(null)
            withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    /** Fits the sensors to what the engine is doing: precise while moving, cheap while staying. */
    private suspend fun adapt(tracker: Tracker, nowMs: Long) {
        val mode = tracker.mode
        val anchor = tracker.stayAnchor
        if (anchor != geofencedAnchor) {
            geofenceActive = geofence.moveTo(anchor)
            geofencedAnchor = anchor
        }
        applyLocationProfile(
            when {
                mode != Mode.STAYING -> LocationProfile.MOVING
                geofenceActive -> LocationProfile.STAYING_GEOFENCED
                else -> LocationProfile.STAYING
            },
        )
        // Fingerprints for the place memory; scans while moving would describe nothing.
        if (mode != Mode.MOVING) wifi?.requestScanIfDue(nowMs)
        if (mode != notifiedMode) {
            notifiedMode = mode
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(mode))
        }
        app.status.update { it.copy(mode = mode, lastEventMs = nowMs) }
    }

    @SuppressLint("MissingPermission") // Checked in onStartCommand.
    private fun applyLocationProfile(next: LocationProfile) {
        if (profile == next) return
        profile = next
        val request = LocationRequest.Builder(next.priority, next.intervalMs)
            .setMinUpdateIntervalMillis(next.minIntervalMs)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission") // Checked right before.
    private fun requestActivityTransitions() {
        // Without activity recognition the engine still detects stays from clustering fixes.
        if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) return
        val transitions = ActivityTransitionReceiver.trackedActivities.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        ActivityRecognition.getClient(this)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionsIntent)
    }

    private fun startInForeground(): Boolean {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW),
        )
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: RuntimeException) {
            // SecurityException or ForegroundServiceStartNotAllowedException, e.g. when the system restarts
            // the service in the background and the app may only use location while in use.
            Log.w(TAG, "Could not start in the foreground", e)
            false
        }
    }

    private fun notification(mode: Mode?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Verweil is recording your location")
            .setContentText(mode.describe())
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 60_000L
        private const val ACTION_STOP = "de.lukasrunge.verweil.STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))

        /** Closes an open stay, queues it for upload and stops. Tracking stays off after a reboot. */
        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun Mode?.describe(): String = when (this) {
    null -> "Starting"
    Mode.MOVING -> "Moving"
    Mode.SETTLING -> "Arriving"
    Mode.STAYING -> "Staying"
    Mode.LEAVING -> "Maybe leaving"
}

private fun Location.toFix() = Fix(
    timeMs = time,
    lat = latitude,
    lon = longitude,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE,
    speed = if (hasSpeed()) speed.toDouble() else null,
    altitude = if (hasAltitude()) altitude else null,
    bearing = if (hasBearing()) bearing.toDouble() else null,
    speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
)
