package de.lukasrunge.verweil.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
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
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.upload.toUploadItems
import de.lukasrunge.verweil.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collects location fixes and activity transitions, runs them through the engine
 * and queues its decisions for upload. Runs as a foreground service of type location.
 */
class TrackingService : LifecycleService() {

    private val app get() = application as VerweilApp

    /** Single queue, so the engine sees events strictly one after another. */
    private val events = Channel<SensorEvent>(Channel.UNLIMITED)
    private val engine = StayEngine()

    private lateinit var fused: FusedLocationProviderClient
    private var stayingProfile: Boolean? = null
    private var started = false

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
        if (started) return START_STICKY
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true

        startInForeground()
        fused = LocationServices.getFusedLocationProviderClient(this)
        applyLocationProfile(staying = false)
        requestActivityTransitions()

        lifecycleScope.launch { app.settings.values.collect { recordRawEvents = it.recordRawEvents } }
        lifecycleScope.launch { app.sensorEvents.collect { events.send(it) } }
        lifecycleScope.launch {
            // Lets engine timeouts fire even when no sensor reports anything.
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                events.send(Tick(System.currentTimeMillis()))
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { processEvents() }

        app.status.update { it.copy(running = true, mode = engine.mode) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // Checked right before removing the transition updates.
    override fun onDestroy() {
        if (started) {
            fused.removeLocationUpdates(locationCallback)
            if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionsIntent)
            }
        }
        events.close()
        app.status.update { it.copy(running = false) }
        super.onDestroy()
    }

    private suspend fun processEvents() {
        val recorder = Recorder(this)
        for (event in events) {
            if (recordRawEvents) recorder.write(event)

            val outputs = engine.process(event)
            if (outputs.isNotEmpty()) {
                app.outbox.add(outputs.flatMap { it.toUploadItems() })
                UploadWorker.enqueue(this)
            }

            applyLocationProfile(staying = engine.mode == Mode.STAYING)
            app.status.update { it.copy(mode = engine.mode, lastEventMs = event.timeMs) }
        }
    }

    /** High accuracy while moving or leaving; a cheap profile while staying put. */
    @SuppressLint("MissingPermission") // Checked in onStartCommand.
    private fun applyLocationProfile(staying: Boolean) {
        if (stayingProfile == staying) return
        stayingProfile = staying
        val request = if (staying) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, STAYING_INTERVAL_MS).build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MOVING_INTERVAL_MS)
                .build()
        }
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

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Verweil is recording your location")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val MOVING_INTERVAL_MS = 5_000L
        private const val STAYING_INTERVAL_MS = 60_000L
        private const val TICK_INTERVAL_MS = 60_000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}

private fun Location.toFix() = Fix(
    timeMs = time,
    lat = latitude,
    lon = longitude,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE,
    speed = if (hasSpeed()) speed.toDouble() else null,
    altitude = if (hasAltitude()) altitude else null,
)
