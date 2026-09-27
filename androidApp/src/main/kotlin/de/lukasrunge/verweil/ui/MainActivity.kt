package de.lukasrunge.verweil.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.TrackingStatus
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.tracking.TrackingService
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by app.status.collectAsStateWithLifecycle()
    val saved by app.settings.values.collectAsStateWithLifecycle(initialValue = null)

    var serverUrl by rememberSaveable(saved) { mutableStateOf(saved?.serverUrl.orEmpty()) }
    var apiKey by rememberSaveable(saved) { mutableStateOf(saved?.apiKey.orEmpty()) }

    // Bumped after every permission dialog so the checks below run again.
    var permissionRound by remember { mutableIntStateOf(0) }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRound++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRound++
    }
    val hasLocation = remember(permissionRound) { context.has(Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasBackground = remember(permissionRound) { context.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

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

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Dawarich URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                app.settings.save((saved ?: SettingsValues()).copy(serverUrl = serverUrl, apiKey = apiKey))
            }
        }) { Text("Save") }

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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasLocation && !status.running, onClick = { TrackingService.start(context) }) {
                Text("Start")
            }
            OutlinedButton(enabled = status.running, onClick = { TrackingService.stop(context) }) {
                Text("Stop")
            }
        }
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

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
