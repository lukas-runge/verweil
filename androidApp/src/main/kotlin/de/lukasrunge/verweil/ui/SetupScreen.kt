package de.lukasrunge.verweil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.notificationPermission
import de.lukasrunge.verweil.tracking.TrackingService

/**
 * After signing in: the permissions tracking needs, one step after another, each with the reason in plain
 * words. Only precise location is required; whatever is skipped shows up on the main screen later.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val requests = rememberAccessRequests()
    val access = requests.access

    val steps = buildList {
        add(SetupStep(R.string.setup_location_title, R.string.setup_location_text, access.preciseLocation, R.string.action_allow, requests.requestLocation))
        add(
            SetupStep(
                R.string.setup_activity_title, R.string.setup_activity_text, access.activityRecognition,
                R.string.action_allow, requests.requestActivityRecognition,
            ),
        )
        if (notificationPermission != null) {
            add(
                SetupStep(
                    R.string.setup_notifications_title, R.string.setup_notifications_text, access.notifications,
                    R.string.action_allow, requests.requestNotifications,
                ),
            )
        }
        add(
            SetupStep(
                R.string.setup_background_title, R.string.setup_background_text, access.backgroundLocation,
                R.string.action_open_settings, requests.requestBackgroundLocation,
                // Android only offers "all the time" once location while in use is granted.
                enabled = access.preciseLocation,
            ),
        )
        add(
            SetupStep(
                R.string.setup_battery_title, R.string.setup_battery_text, access.unrestrictedBattery,
                R.string.action_allow, requests.requestUnrestrictedBattery,
            ),
        )
    }
    val current = steps.indexOfFirst { !it.done && it.enabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
        )
        steps.forEachIndexed { i, step -> StepRow(number = i + 1, step = step, isCurrent = i == current) }
        Spacer(Modifier.height(28.dp))
        Button(
            enabled = access.canTrack,
            onClick = {
                TrackingService.start(context)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_start_tracking)) }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_later))
        }
    }
}

private class SetupStep(
    val title: Int,
    val text: Int,
    val done: Boolean,
    val action: Int,
    val onAction: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun StepRow(number: Int, step: SetupStep, isCurrent: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(bottom = 20.dp)) {
        // Numbered while open, a check once granted: the steps are a sequence.
        Surface(
            shape = CircleShape,
            color = when {
                step.done -> colors.primary
                isCurrent -> colors.primaryContainer
                else -> colors.surface
            },
            contentColor = if (step.done) colors.onPrimary else colors.onPrimaryContainer,
            border = if (step.done || isCurrent) null else BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (step.done) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp))
                } else {
                    Text(number.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(step.title),
                style = MaterialTheme.typography.titleMedium,
                color = if (step.enabled || step.done) colors.onSurface else colors.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            // Only the next step stands out; the others stay available but quiet.
            if (!step.done && isCurrent) {
                FilledTonalButton(
                    onClick = step.onAction,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(stringResource(step.action)) }
            } else if (!step.done) {
                OutlinedButton(onClick = step.onAction, enabled = step.enabled, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(step.action))
                }
            }
        }
    }
}
