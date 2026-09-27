package de.lukasrunge.verweil.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.TrackingStatus
import de.lukasrunge.verweil.UploadStatus
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.upload.OutboxCounts
import de.lukasrunge.verweil.hasPermission
import de.lukasrunge.verweil.tracking.TrackingService
import de.lukasrunge.verweil.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VerweilApp
        setContent {
            MaterialTheme {
                Surface { MainScreen(app) }
            }
        }
    }
}

@Composable
private fun MainScreen(app: VerweilApp) {
    val saved by app.settings.values.collectAsStateWithLifecycle(initialValue = null)
    val settings = saved ?: return
    if (settings.isConfigured) TrackingScreen(app, settings) else LoginScreen(app.settings)
}

@Composable
private fun TrackingScreen(app: VerweilApp, settings: SettingsValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by app.status.collectAsStateWithLifecycle()
    val uploadStatus by app.uploadStatus.collectAsStateWithLifecycle()
    val counts by remember { app.outbox.countsFlow(Dispatchers.IO) }.collectAsStateWithLifecycle(initialValue = null)

    // Sends what waited while signed out or while the key was refused.
    LaunchedEffect(settings.apiKey) { UploadWorker.uploadNow(context) }

    // Bumped after every permission dialog so the checks below run again.
    var permissionRound by remember { mutableIntStateOf(0) }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRound++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRound++
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionRound++
    }
    val hasLocation = remember(permissionRound) { context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasBackground = remember(permissionRound) { context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val unrestricted = remember(permissionRound) { context.ignoresBatteryOptimizations() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Verweil", style = MaterialTheme.typography.headlineMedium)
        Text(status.describe())

        Text(
            if (settings.email.isBlank()) "Connected to ${settings.serverUrl}"
            else "Signed in as ${settings.email} on ${settings.serverUrl}",
        )
        OutlinedButton(onClick = { scope.launch { app.settings.signOut() } }) { Text("Sign out") }

        if (!hasLocation) {
            Button(onClick = { foregroundLauncher.launch(foregroundPermissions()) }) {
                Text("Grant location and activity access")
            }
        } else if (!hasBackground) {
            // Android only grants background location as a separate, second step.
            Button(onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                Text("Allow location all the time")
            }
        }

        if (!unrestricted) {
            Text("Battery optimization can stop tracking in the background.")
            Button(onClick = { batteryLauncher.launch(context.batteryOptimizationRequest()) }) {
                Text("Allow running in the background")
            }
        }
        // Some manufacturers stop background apps regardless of the Android setting.
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://dontkillmyapp.com/") }) {
            Text("Tracking stops anyway? Help for Samsung, Xiaomi and others")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasLocation && !status.running, onClick = { TrackingService.start(context) }) {
                Text("Start")
            }
            OutlinedButton(enabled = status.running, onClick = { TrackingService.stop(context) }) {
                Text("Stop")
            }
        }

        counts?.let { UploadSection(it, uploadStatus, app) }
    }
}

@Composable
private fun UploadSection(counts: OutboxCounts, status: UploadStatus, app: VerweilApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Text("Upload", style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            counts.pending > 0 -> "${counts.pending} waiting for upload"
            counts.rejected > 0 -> "Nothing else waiting for upload"
            else -> "Everything is uploaded"
        },
    )
    status.lastSuccessMs?.let { Text("Last upload ${DateFormat.getTimeInstance().format(Date(it))}") }
    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (counts.pending > 0) {
        OutlinedButton(onClick = { UploadWorker.uploadNow(context) }) { Text("Upload now") }
    }
    if (counts.rejected > 0) {
        Text(
            "Dawarich refused ${counts.rejected} ${if (counts.rejected == 1L) "item" else "items"}: ${counts.lastError}",
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    app.outbox.retryRejected()
                    UploadWorker.uploadNow(context)
                }
            },
        ) { Text("Try again") }
    }
}

private fun TrackingStatus.describe(): String {
    if (!running) return "Not tracking"
    val last = lastEventMs?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "–"
    return "Tracking · ${mode?.name?.lowercase() ?: "starting"} · last event $last"
}

private fun foregroundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.ignoresBatteryOptimizations() =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Asks directly instead of sending the user through the settings list: tracking is the app's whole purpose. */
@SuppressLint("BatteryLife")
private fun Context.batteryOptimizationRequest() =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
