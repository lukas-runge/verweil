package de.lukasrunge.verweil.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lukasrunge.verweil.DawarichDay
import de.lukasrunge.verweil.DawarichTimeline
import de.lukasrunge.verweil.PlaceNames
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.SettingsValues
import de.lukasrunge.verweil.TimelineProblem
import de.lukasrunge.verweil.VerweilApp
import de.lukasrunge.verweil.core.timeline.TimelineEntry
import de.lukasrunge.verweil.core.timeline.mergeTimeline
import de.lukasrunge.verweil.core.timeline.placeCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Any day of the user's history, like Google's timeline: Dawarich's visits and tracks, with what this phone
 * recognised since. Arrows step through the days, the date opens a calendar.
 */
@Composable
fun DaySection(
    app: VerweilApp,
    settings: SettingsValues,
    running: Boolean,
    nowMs: Long,
    lastUploadMs: Long?,
    pullRefresh: Int,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(nowMs) { LocalDate.now(zone) }
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val day = LocalDate.ofEpochDay(epochDay)
    val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val phone by remember(epochDay) { app.journal.segmentsFlow(startMs, endMs, Dispatchers.IO) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dawarich = rememberDawarichDay(app, startMs, endMs, refreshKey = lastUploadMs to pullRefresh)
    val entries = remember(dawarich.entries, phone) { mergeTimeline(dawarich.entries.orEmpty(), phone) }

    // Names for stays only the phone knows; Dawarich names its own.
    val placeNames = remember { PlaceNames(context, app) }
    LaunchedEffect(phone, settings.lookUpPlaceNames) {
        if (settings.lookUpPlaceNames) placeNames.fillIn(phone)
    }

    val openInDawarich = {
        val url = "${settings.serverUrl.trimEnd('/')}/map/v2?panel=timeline&date=$day"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
        Unit
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_pick_day)) { pickDate = true }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(dayLabel(day, today), style = MaterialTheme.typography.headlineSmall)
            Icon(
                ImageVector.vectorResource(R.drawable.ic_calendar_month),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { epochDay-- }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.action_previous_day))
        }
        IconButton(onClick = { epochDay++ }, enabled = day.isBefore(today)) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.action_next_day))
        }
    }

    // Same height with and without the bar, so the timeline does not jump while loading.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (dawarich.loading) {
            LinearProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Places, not stays: the office before and after lunch is one place.
    val stays = entries.placeCount()
    val meters = entries.filterIsInstance<TimelineEntry.Move>().sumOf { it.distanceM }
    if (entries.isNotEmpty()) {
        Caption(pluralStringResource(R.plurals.timeline_summary, stays, stays, distance(meters)))
    }
    dawarich.problem?.let { problem ->
        Caption(problemText(problem, dawarich.fetchedMs), color = MaterialTheme.colorScheme.secondary)
    }
    if (entries.isEmpty()) {
        if (!dawarich.loading) {
            Caption(
                stringResource(
                    when {
                        dawarich.entries == null -> R.string.timeline_empty_phone
                        day == today -> R.string.timeline_empty_today
                        else -> R.string.timeline_empty
                    },
                ),
            )
        }
    } else {
        // Room for the first time, which sits half above the first row.
        Spacer(Modifier.height(20.dp))
        Timeline(
            entries = entries,
            dayStartMs = startMs,
            nowMs = nowMs,
            live = running && day == today,
            // Without Dawarich's answer everything is from the phone; marking every row would say nothing.
            markPending = dawarich.entries != null,
            onOpenMove = openInDawarich,
        )
    }
    TextButton(onClick = openInDawarich, modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
        Icon(ImageVector.vectorResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.action_open_day_in_dawarich))
    }

    if (pickDate) {
        DayPicker(
            day = day,
            today = today,
            onPick = { epochDay = it.toEpochDay() },
            onDismiss = { pickDate = false },
        )
    }
}

/**
 * Dawarich's timeline for the day: the cached copy at once, then a fresh one. Fetches again when the app comes
 * to the front and when [refreshKey] changes, e.g. after an upload, so today catches up with what was sent.
 */
@Composable
private fun rememberDawarichDay(app: VerweilApp, startMs: Long, endMs: Long, refreshKey: Any?): DawarichDay {
    val source = remember { DawarichTimeline(app) }
    var state by remember(startMs) { mutableStateOf(DawarichDay(entries = null, fetchedMs = null, loading = true, problem = null)) }
    var resumed by remember { mutableLongStateOf(0L) }
    LifecycleResumeEffect(Unit) {
        resumed = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    LaunchedEffect(startMs, refreshKey, resumed) {
        // One effect, so a slow cache read can never overwrite a fresh answer.
        if (state.entries == null) {
            source.cached(startMs)?.let { state = DawarichDay(it.entries, it.fetchedMs, loading = true, problem = null) }
        }
        state = state.copy(loading = true)
        val started = System.currentTimeMillis()
        val fresh = source.fetch(startMs, endMs)
        // A nearby server answers in a few milliseconds; the loading bar should still be seen.
        delay(MIN_LOADING_MS - (System.currentTimeMillis() - started))
        state = fresh
    }
    return state
}

private const val MIN_LOADING_MS = 800L

@Composable
private fun problemText(problem: TimelineProblem, fetchedMs: Long?): String {
    val stale = fetchedMs?.let {
        val fetchedToday = java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        stringResource(R.string.timeline_stale, if (fetchedToday) time(it) else dateTime(it))
    }
    return when (problem) {
        TimelineProblem.TOO_OLD -> stringResource(R.string.timeline_too_old)
        TimelineProblem.REFUSED -> stale ?: stringResource(R.string.timeline_refused)
        TimelineProblem.UNREACHABLE, TimelineProblem.OTHER -> stale ?: stringResource(R.string.timeline_unreachable)
    }
}

@Composable
private fun Caption(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(day: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The date picker works in UTC midnights.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.action_show_day)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
