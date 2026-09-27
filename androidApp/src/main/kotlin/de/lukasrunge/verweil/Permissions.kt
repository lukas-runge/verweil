package de.lukasrunge.verweil

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Everything Android has to allow before tracking works well, checked in one go. */
data class Access(
    /** Precise location; approximate alone cannot tell a café from its neighbour. */
    val preciseLocation: Boolean,
    val approximateOnly: Boolean,
    /** "Allow all the time": tracking with the app closed, geofences, resuming after a reboot. */
    val backgroundLocation: Boolean,
    val activityRecognition: Boolean,
    val notifications: Boolean,
    val unrestrictedBattery: Boolean,
    val locationEnabled: Boolean,
) {
    /** Enough to start tracking at all. */
    val canTrack: Boolean get() = preciseLocation
}

fun Context.access(): Access {
    val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    return Access(
        preciseLocation = fine,
        approximateOnly = !fine && hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocation = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        activityRecognition = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION),
        notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        unrestrictedBattery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        locationEnabled = isLocationEnabled(),
    )
}

fun Context.isLocationEnabled(): Boolean = getSystemService(LocationManager::class.java).isLocationEnabled

val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Null below Android 13, where apps may post notifications without asking. */
val notificationPermission: String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

/** For permissions Android no longer asks for, after the user declined twice. */
fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

fun locationSettingsIntent() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

/** Asks directly instead of sending the user through the settings list: tracking is the app's whole purpose. */
@SuppressLint("BatteryLife")
fun Context.batteryOptimizationIntent() =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
