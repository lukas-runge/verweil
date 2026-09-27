package de.lukasrunge.verweil.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.BuildConfig
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.dawarich.formatHeaderLines
import de.lukasrunge.verweil.core.dawarich.parseHeaderLines
import de.lukasrunge.verweil.core.tracking.Tracker
import de.lukasrunge.verweil.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Dialog { DeviceName, Headers, SignOut, ForgetPlaces, ClearTimeline }

@Composable
fun SettingsScreen(app: VerweilApp, settings: SettingsValues, onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val status by app.status.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    // Counted again after every dialog, which is where they change.
    val knownPlaces by produceState(0L, dialog) { value = withContext(Dispatchers.IO) { app.places.count() } }
    val pending by produceState(0L, dialog) { value = withContext(Dispatchers.IO) { app.outbox.counts().pending } }
    val host = settings.serverUrl.toUri().host ?: settings.serverUrl

    SubScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
        SectionTitle(stringResource(R.string.settings_account))
        ListRow(
            R.drawable.ic_dns,
            host,
            detail = settings.email.ifBlank { stringResource(R.string.settings_api_key_account) },
        )
        ListRow(
            R.drawable.ic_smartphone,
            stringResource(R.string.settings_device_name),
            detail = settings.deviceId,
            onClick = { dialog = Dialog.DeviceName },
        )
        ListRow(
            R.drawable.ic_key,
            stringResource(R.string.settings_headers),
            detail = if (settings.customHeaders.isEmpty()) stringResource(R.string.settings_headers_none)
            else settings.customHeaders.keys.joinToString(", "),
            onClick = { dialog = Dialog.Headers },
        )
        ListRow(
            R.drawable.ic_logout,
            stringResource(R.string.action_sign_out),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = { dialog = Dialog.SignOut },
        )

        SectionTitle(stringResource(R.string.settings_tracking))
        SwitchRow(
            R.drawable.ic_location_on,
            stringResource(R.string.settings_place_names),
            stringResource(R.string.settings_place_names_detail),
            checked = settings.lookUpPlaceNames,
        ) { scope.launch { app.settings.setLookUpPlaceNames(it) } }
        ListRow(
            R.drawable.ic_battery_alert,
            stringResource(R.string.settings_reliability),
            detail = stringResource(R.string.settings_reliability_detail),
            onClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
        )

        SectionTitle(stringResource(R.string.settings_on_this_phone))
        ListRow(
            R.drawable.ic_wifi,
            stringResource(R.string.settings_forget_places),
            detail = pluralStringResource(R.plurals.settings_known_places, knownPlaces.toInt(), knownPlaces.toInt()),
            onClick = { dialog = Dialog.ForgetPlaces },
        )
        ListRow(
            R.drawable.ic_timeline,
            stringResource(R.string.settings_clear_timeline),
            detail = stringResource(R.string.settings_clear_timeline_detail),
            onClick = { dialog = Dialog.ClearTimeline },
        )
        SwitchRow(
            R.drawable.ic_database,
            stringResource(R.string.settings_record_raw),
            stringResource(R.string.settings_record_raw_detail),
            checked = settings.recordRawEvents,
        ) { scope.launch { app.settings.setRecordRawEvents(it) } }
        ListRow(
            R.drawable.ic_bug_report,
            stringResource(R.string.diagnostics_title),
            detail = stringResource(R.string.settings_diagnostics_detail),
            onClick = onOpenDiagnostics,
        )

        SectionTitle(stringResource(R.string.settings_about))
        ListRow(
            R.drawable.ic_info,
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            detail = stringResource(R.string.settings_about_detail),
        )
        ListRow(
            R.drawable.ic_open_in_new,
            stringResource(R.string.settings_source),
            detail = "github.com/lukas-runge/verweil",
            onClick = { uriHandler.openUri("https://github.com/lukas-runge/verweil") },
        )
    }

    when (dialog) {
        Dialog.DeviceName -> TextDialog(
            title = stringResource(R.string.settings_device_name),
            text = stringResource(R.string.settings_device_name_detail),
            initial = settings.deviceId,
            singleLine = true,
            onSave = { scope.launch { app.settings.setDeviceId(it) } },
            onDismiss = { dialog = null },
        )
        Dialog.Headers -> TextDialog(
            title = stringResource(R.string.settings_headers),
            text = stringResource(R.string.login_headers_hint),
            initial = formatHeaderLines(settings.customHeaders),
            singleLine = false,
            onSave = { scope.launch { app.settings.setCustomHeaders(parseHeaderLines(it)) } },
            onDismiss = { dialog = null },
        )
        Dialog.SignOut -> SignOutDialog(
            host = host,
            pending = pending,
            onSignOut = { discard ->
                if (status.running) TrackingService.stop(context, closeStay = true)
                app.scope.launch {
                    withContext(Dispatchers.IO) {
                        // A stay paused by an earlier stop ends now, before anything goes to the next account.
                        if (!status.running) Tracker(app.database).finish()
                        if (discard) app.outbox.clear()
                        // The next account has a different history.
                        app.timelineCache.clear()
                    }
                    app.settings.setTrackingEnabled(false)
                    app.settings.signOut()
                }
            },
            onDismiss = { dialog = null },
        )
        Dialog.ForgetPlaces -> ConfirmDialog(
            title = stringResource(R.string.settings_forget_places),
            text = stringResource(R.string.forget_places_text),
            confirm = stringResource(R.string.action_forget),
            destructive = true,
            onConfirm = { scope.launch(Dispatchers.IO) { app.places.clear() } },
            onDismiss = { dialog = null },
        )
        Dialog.ClearTimeline -> ConfirmDialog(
            title = stringResource(R.string.settings_clear_timeline),
            text = stringResource(R.string.clear_timeline_text),
            confirm = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    app.journal.clear()
                    app.timelineCache.clear()
                }
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun TextDialog(
    title: String,
    text: String,
    initial: String,
    singleLine: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(value)
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SignOutDialog(host: String, pending: Long, onSignOut: (discard: Boolean) -> Unit, onDismiss: () -> Unit) {
    var discard by rememberSaveable { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(R.string.sign_out_title),
        text = stringResource(R.string.sign_out_text, host),
        confirm = stringResource(R.string.action_sign_out),
        destructive = true,
        onConfirm = { onSignOut(discard) },
        onDismiss = onDismiss,
        extra = if (pending == 0L) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = discard, onCheckedChange = { discard = it })
                    Text(pluralStringResource(R.plurals.sign_out_discard, pending.toInt(), pending.toInt()))
                }
                Text(
                    stringResource(if (discard) R.string.sign_out_discard_yes else R.string.sign_out_discard_no),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
