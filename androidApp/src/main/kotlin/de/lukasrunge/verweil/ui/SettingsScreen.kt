package de.lukasrunge.verweil.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.BuildConfig
import de.lukasrunge.verweil.MAX_FAVOURITES
import de.lukasrunge.verweil.PICKABLE_MODES
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.dawarich.formatHeaderLines
import de.lukasrunge.verweil.core.dawarich.parseHeaderLines
import de.lukasrunge.verweil.core.timeline.TravelMode
import de.lukasrunge.verweil.core.tracking.Tracker
import de.lukasrunge.verweil.tracking.TrackingService
import de.lukasrunge.verweil.tracking.canUseBluetooth
import de.lukasrunge.verweil.tracking.pairedBluetoothDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Dialog { DeviceName, Headers, Car, FavouriteModes, SignOut, ForgetPlaces, ClearTimeline }

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
    val paired by produceState(emptyMap<String, String>(), dialog) { value = context.pairedBluetoothDevices() }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dialog = Dialog.Car
    }

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
            R.drawable.ic_directions_car,
            stringResource(R.string.settings_car),
            detail = settings.carDevices.map { paired[it] ?: it }.sorted().joinToString(", ")
                .ifEmpty { stringResource(R.string.settings_car_none) },
            onClick = {
                if (context.canUseBluetooth()) dialog = Dialog.Car
                else askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
        )
        ListRow(
            R.drawable.ic_route,
            stringResource(R.string.settings_favourite_modes),
            detail = settings.favouriteModes.map { stringResource(it.shortLabel()) }.joinToString(", "),
            onClick = { dialog = Dialog.FavouriteModes },
        )
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
        Dialog.Car -> CarDialog(
            paired = paired,
            initial = settings.carDevices,
            onSave = { scope.launch { app.settings.setCarDevices(it) } },
            onDismiss = { dialog = null },
        )
        Dialog.FavouriteModes -> FavouriteModesDialog(
            initial = settings.favouriteModes,
            onSave = { scope.launch { app.settings.setFavouriteModes(it) } },
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

/** Which paired Bluetooth devices are cars; Android Auto counts without choosing. */
@Composable
private fun CarDialog(paired: Map<String, String>, initial: Set<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(initial.toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_car)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.car_dialog_text), style = MaterialTheme.typography.bodyMedium)
                if (paired.isEmpty()) {
                    Text(
                        stringResource(R.string.car_dialog_none_paired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                paired.entries.sortedBy { it.value.lowercase() }.forEach { (address, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = if (address in chosen) chosen - address else chosen + address },
                    ) {
                        Checkbox(checked = address in chosen, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(chosen.toSet())
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Up to [MAX_FAVOURITES] modes, offered as tiles when correcting how the user travelled. */
@Composable
private fun FavouriteModesDialog(initial: List<TravelMode>, onSave: (Set<TravelMode>) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(initial.map { it.name }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_favourite_modes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.favourite_modes_text), style = MaterialTheme.typography.bodyMedium)
                PICKABLE_MODES.forEach { mode ->
                    val checked = mode.name in chosen
                    val enabled = checked || chosen.size < MAX_FAVOURITES
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { chosen = if (checked) chosen - mode.name else chosen + mode.name },
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
                        Icon(
                            ImageVector.vectorResource(mode.icon()),
                            contentDescription = null,
                            tint = LocalStateColors.current.moving,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(mode.shortLabel()), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                onSave(PICKABLE_MODES.filter { it.name in chosen }.toSet())
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
