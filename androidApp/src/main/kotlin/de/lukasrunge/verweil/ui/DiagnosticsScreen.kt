package de.lukasrunge.verweil.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.upload.OutboxCounts
import de.lukasrunge.verweil.tracking.Recording
import de.lukasrunge.verweil.tracking.Recordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** What the engine and the sensors are doing, for checking a day against what really happened. */
@Composable
fun DiagnosticsScreen(app: VerweilApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by app.status.collectAsStateWithLifecycle()
    val counts by remember { app.outbox.countsFlow(Dispatchers.IO) }.collectAsStateWithLifecycle(initialValue = null)
    val latest by remember { app.journal.latestFlow(Dispatchers.IO) }.collectAsStateWithLifecycle(initialValue = null)
    val knownPlaces by produceState(0L) { value = withContext(Dispatchers.IO) { app.places.count() } }
    val recordings = remember { Recordings(context) }
    var recordingsVersion by remember { mutableIntStateOf(0) }
    val files by produceState(emptyList<Recording>(), recordingsVersion) { value = withContext(Dispatchers.IO) { recordings.all() } }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val none = stringResource(R.string.diagnostics_none)
    val today = remember { LocalDate.now() }

    SubScreen(title = stringResource(R.string.diagnostics_title), onBack = onBack) {
        SectionTitle(stringResource(R.string.diagnostics_engine))
        Value(stringResource(R.string.diagnostics_running), yesNo(status.running))
        Value(stringResource(R.string.diagnostics_mode), status.mode?.name ?: none)
        Value(stringResource(R.string.diagnostics_activity), status.activity.name)
        Value(stringResource(R.string.diagnostics_profile), status.locationProfile ?: none)
        Value(stringResource(R.string.diagnostics_geofence), yesNo(status.geofenceActive))
        Value(
            stringResource(R.string.diagnostics_segment),
            latest?.let { "${it.kind.name}, ${time(it.startMs)}–${time(it.endMs)}${if (it.ongoing) " …" else ""}" } ?: none,
        )
        latest?.let { Value(stringResource(R.string.diagnostics_position), "%.5f, %.5f".format(Locale.ROOT, it.point.lat, it.point.lon)) }

        SectionTitle(stringResource(R.string.diagnostics_sensors))
        Value(stringResource(R.string.diagnostics_location_enabled), yesNo(status.locationEnabled))
        Value(stringResource(R.string.diagnostics_location_available), yesNo(status.locationAvailable))
        Value(
            stringResource(R.string.diagnostics_last_fix),
            status.lastFixMs?.let { "${time(it)}, ±${status.lastFixAccuracyM?.toInt()} m" } ?: none,
        )
        Value(
            stringResource(R.string.diagnostics_last_wifi),
            status.lastWifiScanMs?.let {
                val count = status.lastWifiAccessPoints ?: 0
                pluralStringResource(R.plurals.diagnostics_wifi_value, count, time(it), count)
            } ?: none,
        )
        Value(stringResource(R.string.diagnostics_known_places), knownPlaces.toString())
        status.lastEventMs?.let { Value(stringResource(R.string.diagnostics_last_event), time(it)) }

        SectionTitle(stringResource(R.string.diagnostics_queue))
        counts?.let { QueueValues(it) }

        SectionTitle(stringResource(R.string.diagnostics_recordings))
        Text(
            pluralStringResource(R.plurals.diagnostics_recordings_detail, Recordings.KEEP_DAYS.toInt(), Recordings.KEEP_DAYS.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        files.forEach { recording ->
            ListRow(
                R.drawable.ic_description,
                dayLabel(recording.day, today),
                detail = Formatter.formatShortFileSize(context, recording.sizeBytes),
                trailing = {
                    Row {
                        IconButton(onClick = { context.startActivity(recordings.shareIntent(listOf(recording))) }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = {
                            recordings.delete(recording)
                            recordingsVersion++
                        }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        }
        if (files.isNotEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { context.startActivity(recordings.shareIntent(files)) }) {
                    Text(stringResource(R.string.action_share_all))
                }
                TextButton(onClick = { confirmDeleteAll = true }) {
                    Text(stringResource(R.string.action_delete_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.delete_recordings_title),
            text = stringResource(R.string.delete_recordings_text),
            confirm = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                recordings.deleteAll()
                recordingsVersion++
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun QueueValues(counts: OutboxCounts) {
    Value(stringResource(R.string.diagnostics_pending), counts.pending.toString())
    Value(stringResource(R.string.diagnostics_rejected), counts.rejected.toString())
    counts.lastError?.let { Value(stringResource(R.string.diagnostics_last_error), it) }
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.diagnostics_yes else R.string.diagnostics_no)

/** A label and its value on one line, values in a fixed-width face so they line up. */
@Composable
private fun Value(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1.3f))
    }
}
