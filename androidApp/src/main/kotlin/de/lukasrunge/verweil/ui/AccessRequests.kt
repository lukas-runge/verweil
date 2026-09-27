package de.lukasrunge.verweil.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.lukasrunge.verweil.Access
import de.lukasrunge.verweil.access
import de.lukasrunge.verweil.appSettingsIntent
import de.lukasrunge.verweil.batteryOptimizationIntent
import de.lukasrunge.verweil.hasPermission
import de.lukasrunge.verweil.locationPermissions
import de.lukasrunge.verweil.notificationPermission

/**
 * The current [Access] and the actions that ask for what is missing. Checks again whenever the app
 * comes back to the front, since the user may have changed a permission in the system settings.
 */
@Stable
class AccessRequests(
    val access: Access,
    val requestLocation: () -> Unit,
    val requestBackgroundLocation: () -> Unit,
    val requestActivityRecognition: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestUnrestrictedBattery: () -> Unit,
)

@Composable
fun rememberAccessRequests(): AccessRequests {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        round++
        onPauseOrDispose { }
    }
    val refresh: () -> Unit = { round++ }

    val location = rememberPermissionLauncher(context, refresh)
    val background = rememberPermissionLauncher(context, refresh)
    val activity = rememberPermissionLauncher(context, refresh)
    val notifications = rememberPermissionLauncher(context, refresh)
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    val access = remember(round) { context.access() }
    return AccessRequests(
        access = access,
        requestLocation = {
            // With approximate location granted, Android only offers the upgrade in its settings.
            if (access.approximateOnly) context.startActivity(context.appSettingsIntent())
            else location(locationPermissions)
        },
        // Android 11 and later open the settings page with "Allow all the time" for this request.
        requestBackgroundLocation = { background(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) },
        requestActivityRecognition = { activity(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) },
        requestNotifications = {
            val permission = notificationPermission
            // Granted but switched off in the settings, or before Android 13: only the settings can turn them on.
            if (permission != null && !context.hasPermission(permission)) notifications(arrayOf(permission))
            else context.startActivity(notificationSettingsIntent(context))
        },
        requestUnrestrictedBattery = { battery.launch(context.batteryOptimizationIntent()) },
    )
}

/**
 * Asks for permissions. Android stops showing its dialog after the user declined twice and answers "denied"
 * at once; then the app's settings page is the only way left, so it opens that instead.
 */
@Composable
private fun rememberPermissionLauncher(context: Context, onResult: () -> Unit): (Array<String>) -> Unit {
    val launchedAt = remember { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val answeredInstantly = SystemClock.elapsedRealtime() - launchedAt.longValue < INSTANT_ANSWER_MS
        if (result.isNotEmpty() && result.values.none { it } && answeredInstantly) {
            context.startActivity(context.appSettingsIntent())
        }
        onResult()
    }
    return { permissions ->
        launchedAt.longValue = SystemClock.elapsedRealtime()
        launcher.launch(permissions)
    }
}

/** Faster than anyone can tap a dialog button: Android did not show one. */
private const val INSTANT_ANSWER_MS = 400L

private fun notificationSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
