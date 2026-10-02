package de.lukasrunge.verweil.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.lukasrunge.verweil.Notifications
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.TrackingStatus
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.model.WifiScan
import de.lukasrunge.verweil.core.tracking.Tracker
import de.lukasrunge.verweil.hasPermission
import de.lukasrunge.verweil.isLocationEnabled
import de.lukasrunge.verweil.ui.formatTime
import de.lukasrunge.verweil.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

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
    private var started = false

    /** What the notification shows; it only changes when this does. */
    private var notificationTitle: String? = null

    @Volatile
    private var wifi: WifiScanner? = null

    private var car: CarDetector? = null

    /** The user stopped tracking, as opposed to the system stopping the service. */
    @Volatile
    private var stopRequested = false

    /** Stopping ends an open stay right away, e.g. on sign-out, instead of pausing it. */
    @Volatile
    private var closeStay = false

    @Volatile
    private var recordRawEvents = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { events.trySend(it.toFix()) }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            app.status.update { it.copy(locationAvailable = availability.isLocationAvailable) }
            refreshNotification()
        }
    }

    /** Location switched on or off in the quick settings. */
    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            app.status.update { it.copy(locationEnabled = isLocationEnabled()) }
            refreshNotification()
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
            requestStop(closeStay = intent.getBooleanExtra(EXTRA_CLOSE_STAY, false))
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !startInForeground()) {
            // Tracking should run but cannot; the watchdog tells the user if it cannot restart it either.
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        app.scope.launch {
            app.settings.setTrackingEnabled(true)
            app.settings.setInterruptionNotified(false)
        }
        Notifications.clearTrackingInterrupted(this)
        TrackingWatchdog.schedule(this)

        fused = LocationServices.getFusedLocationProviderClient(this)
        geofence = StayGeofence(this, EngineConfig().exitRadiusM.toFloat())
        requestActivityTransitions()
        ContextCompat.registerReceiver(
            this,
            locationModeReceiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.status.update { TrackingStatus(running = true, locationEnabled = isLocationEnabled()) }

        lifecycleScope.launch { app.settings.values.collect { recordRawEvents = it.recordRawEvents } }
        lifecycleScope.launch { app.sensorEvents.collect { events.trySend(it) } }
        val carDetector = CarDetector(this, this) { events.trySend(it) }.also { car = it }
        carDetector.start()
        lifecycleScope.launch { app.settings.values.map { it.carDevices }.distinctUntilChanged().collect(carDetector::setCarDevices) }
        lifecycleScope.launch {
            // Lets engine timeouts fire even when no sensor reports anything.
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                events.trySend(Tick(System.currentTimeMillis()))
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val recordings = Recordings(this@TrackingService)
            recordings.pruneOld()
            app.journal.prune(beforeMs = System.currentTimeMillis() - JOURNAL_KEEP_MS)
            // Continues a stay that was going on when the app was killed.
            val tracker = Tracker(app.database)
            val scanner = WifiScanner(this@TrackingService, app.settings.wifiSalt()) { events.trySend(it) }
            withContext(Dispatchers.Main) {
                scanner.start()
                wifi = scanner
            }
            processEvents(tracker, recordings)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // Checked right before removing the transition updates.
    override fun onDestroy() {
        if (started) {
            fused.removeLocationUpdates(locationCallback)
            if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionsIntent)
            }
            unregisterReceiver(locationModeReceiver)
            wifi?.stop()
            car?.stop()
        }
        events.close()
        app.status.update { it.copy(running = false, mode = null) }
        super.onDestroy()
    }

    /** The event loop drains what is queued, pauses or closes an open stay and then stops the service. */
    private fun requestStop(closeStay: Boolean) {
        app.scope.launch { app.settings.setTrackingEnabled(false) }
        TrackingWatchdog.cancel(this)
        if (!started) {
            stopSelf()
            return
        }
        this.closeStay = closeStay
        stopRequested = true
        events.close()
    }

    private suspend fun processEvents(tracker: Tracker, recordings: Recordings) {
        adapt(tracker, System.currentTimeMillis())
        for (event in events) {
            if (recordRawEvents) recordings.write(event)
            if (tracker.process(event).isNotEmpty()) UploadWorker.enqueue(this)
            observe(event)
            adapt(tracker, event.timeMs)
        }
        if (stopRequested) {
            val outputs = if (closeStay) tracker.finish() else tracker.pause(System.currentTimeMillis())
            if (outputs.isNotEmpty()) UploadWorker.enqueue(this)
            geofence.moveTo(null)
            withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    /** Keeps what the diagnostics screen shows about the sensors up to date. */
    private fun observe(event: SensorEvent) {
        when (event) {
            is Fix -> app.status.update { it.copy(lastFixMs = event.timeMs, lastFixAccuracyM = event.accuracy) }
            is WifiScan -> app.status.update { it.copy(lastWifiScanMs = event.timeMs, lastWifiAccessPoints = event.bssids.size) }
            else -> Unit
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
        val next = when {
            mode != Mode.STAYING -> LocationProfile.MOVING
            geofenceActive -> LocationProfile.STAYING_GEOFENCED
            else -> LocationProfile.STAYING
        }
        applyLocationProfile(next)
        // Fingerprints for the place memory; scans while moving would describe nothing.
        if (mode != Mode.MOVING) wifi?.requestScanIfDue(nowMs)
        val modeChanged = mode != app.status.value.mode
        app.status.update {
            it.copy(
                mode = mode,
                activity = tracker.activity,
                lastEventMs = nowMs,
                locationProfile = next.name,
                geofenceActive = geofenceActive,
            )
        }
        if (modeChanged) refreshNotification()
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
        val title = getString(R.string.notification_starting)
        notificationTitle = title
        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.TRACKING_ID,
                Notifications.tracking(this, title, stopIntent()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: RuntimeException) {
            // SecurityException or ForegroundServiceStartNotAllowedException, e.g. when the system restarts
            // the service in the background and the app may only use location while in use.
            Log.w(TAG, "Could not start in the foreground", e)
            false
        }
    }

    /** Shows the current state in the notification: where the user is and since when, or what is wrong. */
    private fun refreshNotification() {
        lifecycleScope.launch {
            val status = app.status.value
            val title = when {
                !status.locationEnabled -> getString(R.string.notification_location_off)
                status.mode == Mode.STAYING -> {
                    val since = withContext(Dispatchers.IO) {
                        app.journal.latest()?.takeIf { it.kind == SegmentKind.STAY && it.ongoing }?.startMs
                    }
                    if (since == null) getString(R.string.mode_staying)
                    else getString(R.string.notification_staying_since, formatTime(this@TrackingService, since))
                }
                status.mode == Mode.MOVING -> getString(R.string.mode_moving)
                status.mode == Mode.SETTLING -> getString(R.string.mode_settling)
                status.mode == Mode.LEAVING -> getString(R.string.mode_leaving)
                else -> getString(R.string.notification_starting)
            }
            if (title == notificationTitle || !started) return@launch
            notificationTitle = title
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.TRACKING_ID, Notifications.tracking(this@TrackingService, title, stopIntent()))
        }
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TrackingService"
        private const val TICK_INTERVAL_MS = 60_000L
        private const val ACTION_STOP = "de.lukasrunge.verweil.STOP"
        private const val EXTRA_CLOSE_STAY = "close_stay"

        /** The timeline on the phone covers this long; Dawarich keeps the history. */
        private val JOURNAL_KEEP_MS = 30.days.inWholeMilliseconds

        /**
         * Starts tracking if Android allows it. Returns false without precise location, which the service
         * cannot run without, or when Android refuses a start from the background.
         */
        fun start(context: Context): Boolean {
            if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: in the background without an exemption.
                Log.w(TAG, "Could not start tracking", e)
                false
            }
        }

        /**
         * Stops tracking; it stays off after a reboot. An open stay is paused, see [Tracker.pause], or with
         * [closeStay] ends now and is queued for upload.
         */
        fun stop(context: Context, closeStay: Boolean = false) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CLOSE_STAY, closeStay),
            )
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
    bearing = if (hasBearing()) bearing.toDouble() else null,
    speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
)
