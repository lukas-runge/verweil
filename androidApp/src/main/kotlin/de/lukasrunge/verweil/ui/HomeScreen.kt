package de.lukasrunge.verweil.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.PlaceNames
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.TrackingStatus
import de.lukasrunge.verweil.UploadState
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.journal.Segment
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.timeline.nameNear
import de.lukasrunge.verweil.core.timeline.toTravelMode
import de.lukasrunge.verweil.core.upload.OutboxCounts
import de.lukasrunge.verweil.locationSettingsIntent
import de.lukasrunge.verweil.tracking.TrackingService
import de.lukasrunge.verweil.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** The main screen: what Verweil thinks you are doing, what needs fixing, the day so far, and the upload. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: VerweilApp, settings: SettingsValues, onOpenSettings: () -> Unit) {
    val status by app.status.collectAsStateWithLifecycle()
    val upload by app.settings.uploadState.collectAsStateWithLifecycle(initialValue = UploadState())
    val counts by remember { app.outbox.countsFlow(Dispatchers.IO) }.collectAsStateWithLifecycle(initialValue = null)
    val latest by remember { app.journal.latestFlow(Dispatchers.IO) }.collectAsStateWithLifecycle(initialValue = null)
    val requests = rememberAccessRequests()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            Hero(app, status, settings, latest, nowMs, requests)
            // Signing in again keeps the queue and tracking; the queue goes out once the key works.
            Notices(requests, upload, onSignInAgain = { app.scope.launch { app.settings.signOut() } })
            DaySection(app, settings, running = status.running, nowMs = nowMs, lastUploadMs = upload.lastSuccessMs)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            counts?.let { UploadSection(app, settings, it, upload) }
        }
    }
}

/** The big statement at the top: the engine's state in words, with the one action that fits it. */
@Composable
private fun Hero(app: VerweilApp, status: TrackingStatus, settings: SettingsValues, latest: Segment?, nowMs: Long, requests: AccessRequests) {
    val context = LocalContext.current
    val colors = LocalStateColors.current
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val access = requests.access

    val stay = latest?.takeIf { it.kind == SegmentKind.STAY && it.ongoing }
    // The name the timeline shows: Dawarich's for a place it knows, otherwise the phone's.
    val stayName by produceState(stay?.placeName, stay?.id, stay?.placeName, nowMs) {
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        value = stay?.let { s ->
            withContext(Dispatchers.IO) { app.timelineCache.get(dayStart) }?.entries?.nameNear(s.point) ?: s.placeName
        }
    }
    val move = latest?.takeIf { it.kind == SegmentKind.MOVE && it.ongoing }
    val running = status.running
    // While tracking runs the service watches the switch; otherwise it is checked when the app comes to the front.
    val locationOff = if (running) !status.locationEnabled else !access.locationEnabled
    val (title, detail, accent) = when {
        locationOff -> Triple(
            stringResource(R.string.hero_location_off),
            stringResource(R.string.hero_location_off_detail),
            MaterialTheme.colorScheme.error,
        )
        running -> when (status.mode) {
            Mode.STAYING -> Triple(
                stayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mode_staying),
                stay?.let { stringResource(R.string.hero_since_for, time(it.startMs), duration(nowMs - it.startMs)) }
                    ?: stringResource(R.string.hero_staying_detail),
                colors.staying,
            )
            Mode.MOVING -> Triple(
                stringResource(status.activity.toTravelMode().label()),
                move?.let { stringResource(R.string.hero_since_distance, time(it.startMs), distance(it.distanceM)) }
                    ?: stringResource(R.string.hero_moving_detail),
                colors.moving,
            )
            Mode.SETTLING -> Triple(stringResource(R.string.mode_settling), stringResource(R.string.hero_settling_detail), colors.staying)
            Mode.LEAVING -> Triple(stringResource(R.string.mode_leaving), stringResource(R.string.hero_leaving_detail), colors.moving)
            null -> Triple(stringResource(R.string.mode_starting), stringResource(R.string.hero_starting_detail), colors.staying)
        }
        settings.trackingEnabled && !access.canTrack -> Triple(
            stringResource(R.string.hero_interrupted),
            stringResource(R.string.hero_interrupted_no_location),
            MaterialTheme.colorScheme.error,
        )
        else -> Triple(
            stringResource(R.string.hero_off),
            stringResource(R.string.hero_off_detail),
            MaterialTheme.colorScheme.outline,
        )
    }

    Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Filled while recording, hollow while not.
            Surface(
                shape = CircleShape,
                color = if (running) accent else Color.Transparent,
                border = if (running) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(10.dp),
            ) {}
            Text(
                stringResource(if (running) R.string.hero_recording else R.string.hero_not_recording),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Place names run long ("Karl-Liebknecht-Straße 8, Berlin"): a smaller size, and hyphens instead of cutting words.
        Text(
            title,
            style = (if (title.length > 14) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium)
                .copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Heading),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (running && !locationOff) {
            val fix = status.lastFixMs
            Text(
                when {
                    !status.locationAvailable -> stringResource(R.string.hero_no_signal)
                    fix != null && status.lastFixAccuracyM != null ->
                        stringResource(R.string.hero_last_fix, time(fix), status.lastFixAccuracyM.toInt())
                    else -> stringResource(R.string.hero_waiting_for_fix)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            locationOff -> Button(onClick = { context.startActivity(locationSettingsIntent()) }) {
                Text(stringResource(R.string.action_turn_on_location))
            }
            running -> OutlinedButton(onClick = { confirmStop = true }) { Text(stringResource(R.string.action_stop_tracking)) }
            !access.canTrack -> Button(onClick = requests.requestLocation) { Text(stringResource(R.string.action_allow_location)) }
            else -> Button(onClick = { TrackingService.start(context) }) {
                Icon(ImageVector.vectorResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_start_tracking))
            }
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.stop_title),
            text = stringResource(R.string.stop_text),
            confirm = stringResource(R.string.action_stop_tracking),
            onConfirm = { TrackingService.stop(context) },
            onDismiss = { confirmStop = false },
        )
    }
}

/** What stands between the user and reliable tracking, most serious first. */
@Composable
private fun Notices(requests: AccessRequests, upload: UploadState, onSignInAgain: () -> Unit) {
    val access = requests.access
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (upload.authRefused) {
            Notice(
                R.drawable.ic_key, stringResource(R.string.notice_auth_title), stringResource(R.string.notice_auth_text), Tone.Error,
                action = stringResource(R.string.action_sign_in_again), onAction = onSignInAgain,
            )
        }
        if (access.approximateOnly) {
            Notice(
                R.drawable.ic_my_location, stringResource(R.string.notice_approximate_title), stringResource(R.string.notice_approximate_text),
                Tone.Error, action = stringResource(R.string.action_allow_precise), onAction = requests.requestLocation,
            )
        }
        if (access.canTrack && !access.backgroundLocation) {
            Notice(
                R.drawable.ic_location_on, stringResource(R.string.notice_background_title), stringResource(R.string.notice_background_text),
                Tone.Warning, action = stringResource(R.string.action_allow_all_the_time), onAction = requests.requestBackgroundLocation,
            )
        }
        if (!access.unrestrictedBattery) {
            Notice(
                R.drawable.ic_battery_alert, stringResource(R.string.notice_battery_title), stringResource(R.string.notice_battery_text),
                Tone.Warning, action = stringResource(R.string.action_allow_background), onAction = requests.requestUnrestrictedBattery,
            )
        }
        if (!access.activityRecognition) {
            Notice(
                R.drawable.ic_directions_walk, stringResource(R.string.notice_activity_title), stringResource(R.string.notice_activity_text),
                Tone.Info, action = stringResource(R.string.action_allow), onAction = requests.requestActivityRecognition,
            )
        }
        if (!access.notifications) {
            Notice(
                R.drawable.ic_notifications, stringResource(R.string.notice_notifications_title), stringResource(R.string.notice_notifications_text),
                Tone.Info, action = stringResource(R.string.action_allow), onAction = requests.requestNotifications,
            )
        }
    }
}

@Composable
private fun UploadSection(app: VerweilApp, settings: SettingsValues, counts: OutboxCounts, upload: UploadState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(
        stringResource(R.string.upload_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
    val lastSuccess = upload.lastSuccessMs?.let { stringResource(R.string.upload_last, time(it)) }
    when {
        upload.authRefused -> ListRow(
            R.drawable.ic_cloud_off,
            pluralStringResource(R.plurals.upload_waiting, counts.pending.toInt(), counts.pending.toInt()),
            detail = stringResource(R.string.upload_auth_refused),
            titleColor = MaterialTheme.colorScheme.error,
        )
        counts.pending > 0 -> ListRow(
            R.drawable.ic_cloud_upload,
            pluralStringResource(R.plurals.upload_waiting, counts.pending.toInt(), counts.pending.toInt()),
            detail = upload.error?.let { stringResource(R.string.upload_failed, it) } ?: lastSuccess
                ?: stringResource(R.string.upload_soon),
            trailing = {
                TextButton(onClick = { UploadWorker.uploadNow(context) }) { Text(stringResource(R.string.action_upload_now)) }
            },
        )
        else -> ListRow(
            R.drawable.ic_cloud_done,
            stringResource(R.string.upload_done),
            detail = lastSuccess,
        )
    }
    if (counts.rejected > 0) {
        ListRow(
            R.drawable.ic_error,
            pluralStringResource(R.plurals.upload_rejected, counts.rejected.toInt(), counts.rejected.toInt()),
            detail = counts.lastError,
            titleColor = MaterialTheme.colorScheme.error,
            trailing = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        app.outbox.retryRejected()
                        UploadWorker.uploadNow(context)
                    }
                }) { Text(stringResource(R.string.action_retry)) }
            },
        )
    }
    ListRow(
        R.drawable.ic_open_in_new,
        stringResource(R.string.action_open_dawarich),
        detail = settings.serverUrl.toUri().host ?: settings.serverUrl,
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, "${settings.serverUrl.trimEnd('/')}/map".toUri()))
            } catch (_: ActivityNotFoundException) {
            }
        },
    )
    Box(Modifier.height(8.dp))
}
