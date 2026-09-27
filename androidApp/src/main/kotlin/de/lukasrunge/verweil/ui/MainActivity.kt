package de.lukasrunge.verweil.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.tracking.TrackingService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VerweilApp
        setContent {
            VerweilTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { App(app) }
            }
        }
    }
}

private enum class Screen { Home, Settings, Diagnostics }

@Composable
private fun App(app: VerweilApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by app.settings.values.collectAsStateWithLifecycle(initialValue = null)
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.Home)) }

    // Tracking should run but Android stopped it: the app in front may start it again, even without
    // "Allow all the time", which the background watchdog needs.
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            val settings = app.settings.current()
            if (settings.trackingEnabled && settings.isConfigured && !app.status.value.running) TrackingService.start(context)
        }
        onPauseOrDispose { job.cancel() }
    }

    val settings = saved ?: return
    // After signing out, the next account starts on the main screen.
    LaunchedEffect(settings.isConfigured) { if (!settings.isConfigured) stack = listOf(Screen.Home) }
    when {
        !settings.isConfigured -> LoginScreen(app.settings)
        !settings.setupDone -> SetupScreen(onDone = { scope.launch { app.settings.setSetupDone() } })
        else -> {
            BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
            val open = { screen: Screen -> stack = stack + screen }
            val back = { stack = stack.dropLast(1) }
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = {
                    // Deeper screens come in from the right, going back reverses it.
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (slideInHorizontally { direction * it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -direction * it / 5 } + fadeOut())
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(app, settings, onOpenSettings = { open(Screen.Settings) })
                    Screen.Settings -> SettingsScreen(app, settings, onBack = back, onOpenDiagnostics = { open(Screen.Diagnostics) })
                    Screen.Diagnostics -> DiagnosticsScreen(app, onBack = back)
                }
            }
        }
    }
}
