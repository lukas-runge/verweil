package de.lukasrunge.verweil.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.lukasrunge.verweil.DawarichTimeline
import de.lukasrunge.verweil.MAX_FAVOURITES
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.timeline.Source
import de.lukasrunge.verweil.core.timeline.TimelineEntry
import de.lukasrunge.verweil.core.timeline.TravelMode
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app knows about a row of the timeline, with what can be done with it: a stay opens in a map app,
 * a move of Dawarich's gets its mode corrected, and either opens in Dawarich.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(
    entry: TimelineEntry,
    dayStartMs: Long,
    dayEndMs: Long,
    nowMs: Long,
    live: Boolean,
    favourites: List<TravelMode>,
    source: DawarichTimeline,
    onChanged: () -> Unit,
    onOpenInDawarich: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val day = dayStartMs until dayEndMs
            val endMs = if (live) nowMs else entry.endMs
            val trackId = (entry as? TimelineEntry.Move)?.trackId
            when {
                editing && entry is TimelineEntry.Move && trackId != null -> ModePicker(
                    current = entry.mode,
                    trackId = trackId,
                    favourites = favourites,
                    source = source,
                    onChanged = {
                        onChanged()
                        close()
                    },
                    onOpenInDawarich = onOpenInDawarich,
                    onBack = { editing = false },
                )
                entry is TimelineEntry.Stay -> StayDetails(entry, span(entry.startMs, endMs, live, day), endMs - entry.startMs, onOpenInDawarich)
                entry is TimelineEntry.Move -> MoveDetails(
                    entry,
                    span(entry.startMs, endMs, live, day),
                    endMs - entry.startMs,
                    onEdit = { editing = true },
                    onOpenInDawarich = onOpenInDawarich,
                )
            }
        }
    }
}

/** "13:08 – 13:39", "seit 13:27" while it goes on; times on another day carry their weekday. */
@Composable
private fun span(startMs: Long, endMs: Long, live: Boolean, day: LongRange): String {
    @Composable
    fun at(ms: Long) = if (ms in day) time(ms) else "${weekday(ms)} ${time(ms)}"
    return if (live) stringResource(R.string.timeline_since, at(startMs)) else "${at(startMs)} – ${at(endMs)}"
}

@Composable
private fun StayDetails(stay: TimelineEntry.Stay, span: String, durationMs: Long, onOpenInDawarich: () -> Unit) {
    val context = LocalContext.current
    PlaceName(stay.name ?: stringResource(R.string.timeline_stay), stay.tags, style = MaterialTheme.typography.headlineSmall)
    Text("$span · ${duration(durationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    PendingNote(stay.source)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stay.point?.let { point ->
            FilledTonalButton(onClick = {
                // Any map app; "geo:" with a query pins the exact point.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${point.lat},${point.lon}".toUri()))
                } catch (_: ActivityNotFoundException) {
                }
            }) { Text(stringResource(R.string.action_open_in_maps)) }
        }
        OutlinedButton(onClick = onOpenInDawarich) { Text(stringResource(R.string.action_open_in_dawarich)) }
    }
}

@Composable
private fun MoveDetails(move: TimelineEntry.Move, span: String, durationMs: Long, onEdit: () -> Unit, onOpenInDawarich: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            ImageVector.vectorResource(move.mode.icon()),
            contentDescription = null,
            tint = LocalStateColors.current.moving,
            modifier = Modifier.size(24.dp),
        )
        Text(
            stringResource(move.mode.label()),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        // Only Dawarich's tracks can be corrected; what only the phone saw is not in Dawarich yet.
        if (move.trackId != null) TextButton(onClick = onEdit) { Text(stringResource(R.string.action_change)) }
    }
    Text("$span · ${duration(durationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        listOfNotNull(distance(move.distanceM), averageSpeed(move.distanceM, durationMs)).joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PendingNote(move.source)
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onOpenInDawarich) { Text(stringResource(R.string.action_open_in_dawarich)) }
}

@Composable
private fun PendingNote(source: Source) {
    if (source == Source.DAWARICH) return
    Text(
        stringResource(R.string.sheet_phone_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** "Ø 12 km/h", or nothing for a move too short to say. */
@Composable
private fun averageSpeed(meters: Double, durationMs: Long): String? {
    if (durationMs < 60_000 || meters < 50) return null
    val kmh = meters / 1000 / (durationMs / 3_600_000.0)
    val format = NumberFormat.getNumberInstance(LocalResources.current.configuration.locales[0])
        .apply { maximumFractionDigits = if (kmh < 10) 1 else 0 }
    return stringResource(R.string.sheet_average_speed, format.format(kmh))
}

/** What the mode list is waiting for, or why it cannot be shown. */
private sealed interface Modes {
    data object Loading : Modes
    data class Ready(val modes: List<TravelMode>) : Modes

    /** The server has no track segments API: modes can only be changed on Dawarich's own page. */
    data object Unsupported : Modes
    data object Failed : Modes
}

/**
 * The user's favourite modes as large tiles, the rest Dawarich allows behind "More". Picking one corrects Dawarich's
 * track [trackId]: Dawarich takes it for the track, its statistics and its own timeline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModePicker(
    current: TravelMode,
    trackId: Long,
    favourites: List<TravelMode>,
    source: DawarichTimeline,
    onChanged: () -> Unit,
    onOpenInDawarich: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val modes by produceState<Modes>(Modes.Loading, trackId) {
        value = try {
            Modes.Ready(source.travelModes(trackId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DawarichException && e.status == 404) Modes.Unsupported else Modes.Failed
        }
    }
    var saving by remember { mutableStateOf<TravelMode?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    // A current mode outside the favourites shows the rest right away, so it is visible as picked.
    var more by remember { mutableStateOf(current !in favourites) }

    val pick: (TravelMode) -> Unit = { mode ->
        if (mode == current) {
            onBack()
        } else if (saving == null) {
            saving = mode
            saveFailed = false
            scope.launch {
                try {
                    source.setTravelMode(trackId, mode)
                    onChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    saving = null
                    saveFailed = true
                }
            }
        }
    }

    Text(stringResource(R.string.travel_mode_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    when (val state = modes) {
        Modes.Unsupported -> Text(stringResource(R.string.travel_mode_unsupported))
        Modes.Failed -> Text(stringResource(R.string.travel_mode_failed))
        Modes.Loading, is Modes.Ready -> {
            val enabled = (state as? Modes.Ready)?.modes
            val tiles = favourites.filter { enabled == null || it in enabled }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { mode ->
                    ModeTile(
                        mode = mode,
                        selected = (saving ?: current) == mode,
                        enabled = enabled != null && saving == null,
                        onClick = { pick(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps tiles the same width when fewer than four are favourites.
                repeat(MAX_FAVOURITES - tiles.size) { Spacer(Modifier.weight(1f)) }
            }
            if (enabled == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            val rest = enabled.orEmpty().filter { it !in tiles }
            if (rest.isNotEmpty()) {
                TextButton(onClick = { more = !more }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(if (more) R.string.action_fewer else R.string.action_more))
                }
                if (more) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rest.forEach { mode ->
                            FilterChip(
                                selected = (saving ?: current) == mode,
                                enabled = saving == null,
                                onClick = { pick(mode) },
                                label = { Text(stringResource(mode.shortLabel())) },
                                leadingIcon = {
                                    Icon(ImageVector.vectorResource(mode.icon()), contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (saving != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    if (saveFailed) {
        Text(
            stringResource(R.string.travel_mode_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onOpenInDawarich) { Text(stringResource(R.string.action_open_in_dawarich)) }
    }
}

@Composable
private fun ModeTile(mode: TravelMode, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.secondary else colors.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
        ) {
            Icon(
                ImageVector.vectorResource(mode.icon()),
                contentDescription = null,
                tint = LocalStateColors.current.moving,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(0.dp).height(6.dp))
            Text(
                stringResource(mode.shortLabel()),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
