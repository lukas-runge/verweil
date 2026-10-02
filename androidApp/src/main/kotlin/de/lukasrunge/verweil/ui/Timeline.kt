package de.lukasrunge.verweil.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.timeline.Source
import de.lukasrunge.verweil.core.timeline.TimelineEntry

/** Longer between two segments, and the timeline shows that nothing was recorded. */
private const val GAP_MS = 20 * 60_000L

/** How strongly what happened on another day than the one shown is faded: its time, and its rail at the far end. */
private const val OTHER_DAY_ALPHA = 0.38f

private sealed interface TimelineRow {
    data class Item(val entry: TimelineEntry) : TimelineRow
    data class Gap(val fromMs: Long, val toMs: Long) : TimelineRow

    /** The part of [entry] on the day before ([before]) or after the day shown, faded beyond the midnight line. */
    data class OtherDay(val entry: TimelineEntry, val before: Boolean) : TimelineRow
}

/**
 * The day as a line: a stay is a solid block, a move a dotted path, a gap a faint dashed line.
 * An entry that began the day before [dayStartMs] or ends after [dayEndMs] has its part on that day as a faded stub
 * beyond a midnight line, and counts the time of this day.
 * With [markPending], the phone's newest entries say that they are not in Dawarich yet.
 */
@Composable
fun Timeline(
    entries: List<TimelineEntry>,
    dayStartMs: Long,
    dayEndMs: Long,
    nowMs: Long,
    live: Boolean,
    markPending: Boolean,
    onOpen: (TimelineEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(entries, dayStartMs, dayEndMs) {
        buildList {
            entries.firstOrNull()?.takeIf { it.startMs < dayStartMs }?.let { add(TimelineRow.OtherDay(it, before = true)) }
            // Measured from the furthest end so far: a long stay can reach past the move listed after it.
            var coveredUntil: Long? = null
            entries.forEach { entry ->
                val until = coveredUntil
                if (until != null && entry.startMs - until > GAP_MS) add(TimelineRow.Gap(until, entry.startMs))
                add(TimelineRow.Item(entry))
                coveredUntil = maxOf(until ?: entry.endMs, entry.endMs)
            }
            entries.lastOrNull()?.takeIf { !it.ongoing && it.endMs > dayEndMs }?.let { add(TimelineRow.OtherDay(it, before = false)) }
        }
    }
    Column(modifier = modifier) {
        rows.forEach { row ->
            when (row) {
                is TimelineRow.Item -> EntryRow(
                    entry = row.entry,
                    dayStartMs = dayStartMs,
                    dayEndMs = dayEndMs,
                    nowMs = nowMs,
                    // Only what is still going on, and only while tracking runs.
                    live = live && row.entry.ongoing,
                    note = if (!markPending) null else when (row.entry.source) {
                        Source.PHONE -> R.string.timeline_pending
                        // What the phone saw where Dawarich has a hole just fills it; Dawarich keeps its own view.
                        Source.PHONE_MISSING, Source.DAWARICH -> null
                    },
                    onClick = { onOpen(row.entry) },
                )
                is TimelineRow.Gap -> GapRow(row)
                is TimelineRow.OtherDay -> OtherDayRow(row, dayEndMs, onClick = { onOpen(row.entry) })
            }
        }
        // The last boundary: when the day's last stay or move ended, unless it is still going on.
        val last = rows.lastOrNull()
        val lastEntry = (last as? TimelineRow.Item)?.entry ?: (last as? TimelineRow.OtherDay)?.entry
        if (lastEntry != null && !lastEntry.ongoing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(horizontal = 24.dp),
            ) {
                BoundaryTime(lastEntry.endMs, otherDay = last is TimelineRow.OtherDay)
            }
        }
    }
}

/**
 * A time on the boundary between two rows, where one stay or move hands over to the next:
 * centred on the top edge of its row, taking no height of its own. A time on [otherDay] is faded, its weekday above it.
 */
@Composable
private fun BoundaryTime(epochMs: Long, otherDay: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.let { if (otherDay) it.copy(alpha = OTHER_DAY_ALPHA) else it }
    Layout(
        content = {
            Text(time(epochMs), style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.End)
            if (otherDay) Text(weekday(epochMs), style = MaterialTheme.typography.labelSmall, color = color, textAlign = TextAlign.End)
        },
        modifier = Modifier.width(52.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val clock = measurables[0].measure(loose)
        val weekday = measurables.getOrNull(1)?.measure(loose)
        layout(constraints.maxWidth, 0) {
            val clockY = -clock.height / 2
            clock.place(constraints.maxWidth - clock.width, clockY)
            weekday?.place(constraints.maxWidth - weekday.width, clockY - weekday.height)
        }
    }
}

/** A dashed line across the top edge of a row that begins at midnight, named at its end. */
@Composable
private fun Modifier.midnightLine(): Modifier {
    val color = MaterialTheme.colorScheme.outline
    return drawBehind {
        // From the rail to the end of the row, clear of the time left of it.
        val startX = (24 + 52 + 8).dp.toPx()
        drawLine(
            color = color,
            start = Offset(startX, 0f),
            end = Offset(size.width - 24.dp.toPx(), 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
private fun MidnightLabel(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.timeline_midnight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        // Centred on the line, taking no height; the background inside keeps the line out of the text.
        modifier = modifier
            .padding(end = 24.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
            }
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 4.dp),
    )
}

/** The faded stub of an entry's part on another day: yesterday's start above midnight, or tomorrow's end below. */
@Composable
private fun OtherDayRow(row: TimelineRow.OtherDay, dayEndMs: Long, onClick: () -> Unit) {
    val colors = LocalStateColors.current
    val isStay = row.entry is TimelineEntry.Stay
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .then(if (row.before) Modifier else Modifier.midnightLine())
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp),
        ) {
            // The midnight line speaks for itself; only the start on the day before has a time here.
            if (row.before) BoundaryTime(row.entry.startMs, otherDay = true) else Spacer(Modifier.width(52.dp))
            // Runs on from the row across midnight and fades away from it.
            Rail(
                kind = if (isStay) RailKind.Stay else RailKind.Move,
                color = if (isStay) colors.staying else colors.moving,
                live = false,
                openTop = !row.before,
                openBottom = row.before,
                fade = if (row.before) RailFade.In else RailFade.Out,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight(),
            )
        }
        if (!row.before) MidnightLabel(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun EntryRow(
    entry: TimelineEntry,
    dayStartMs: Long,
    dayEndMs: Long,
    nowMs: Long,
    live: Boolean,
    note: Int?,
    onClick: () -> Unit,
) {
    val colors = LocalStateColors.current
    val endMs = if (live) nowMs else entry.endMs
    val fromYesterday = entry.startMs < dayStartMs
    val intoTomorrow = !entry.ongoing && entry.endMs > dayEndMs
    val isStay = entry is TimelineEntry.Stay
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = if (isStay) 72.dp else 60.dp)
                .then(if (fromYesterday) Modifier.midnightLine() else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp),
        ) {
            if (fromYesterday) Spacer(Modifier.width(52.dp)) else BoundaryTime(entry.startMs)
            Rail(
                kind = if (isStay) RailKind.Stay else RailKind.Move,
                color = if (isStay) colors.staying else colors.moving,
                live = live,
                // Joins the faded stub of its part on the other day.
                openTop = fromYesterday,
                openBottom = intoTomorrow,
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val detail = when (entry) {
                    is TimelineEntry.Stay -> when {
                        live -> stringResource(R.string.timeline_since, duration(endMs - entry.startMs))
                        // Tracking stopped without ending the stay; it goes on when tracking resumes.
                        entry.ongoing -> stringResource(R.string.timeline_open_since, time(entry.startMs))
                        // Across midnight, a stay counts the time of this day; the whole stay is in the line below.
                        fromYesterday || intoTomorrow -> stringResource(
                            R.string.timeline_today_share,
                            duration(minOf(endMs, dayEndMs) - maxOf(entry.startMs, dayStartMs)),
                        )
                        else -> duration(endMs - entry.startMs)
                    }
                    is TimelineEntry.Move -> stringResource(R.string.timeline_move_detail, distance(entry.distanceM), duration(endMs - entry.startMs))
                }
                val otherDays = listOfNotNull(
                    if (fromYesterday) stringResource(R.string.timeline_from_other_day, weekday(entry.startMs), time(entry.startMs)) else null,
                    if (intoTomorrow) stringResource(R.string.timeline_until_other_day, weekday(entry.endMs), time(entry.endMs)) else null,
                    if (isStay && !live && (fromYesterday || intoTomorrow)) stringResource(R.string.timeline_total, duration(endMs - entry.startMs)) else null,
                )
                when (entry) {
                    is TimelineEntry.Stay -> PlaceName(
                        entry.name ?: stringResource(R.string.timeline_stay),
                        entry.tags,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    is TimelineEntry.Move -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            ImageVector.vectorResource(entry.mode.icon()),
                            contentDescription = null,
                            tint = colors.moving,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp),
                        )
                        Text(
                            stringResource(entry.mode.label()),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (otherDays.isNotEmpty()) {
                    Text(
                        otherDays.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (note != null && !live) {
                    Text(
                        stringResource(note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        if (fromYesterday) MidnightLabel(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun GapRow(gap: TimelineRow.Gap) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 44.dp)
            .padding(horizontal = 24.dp),
    ) {
        BoundaryTime(gap.fromMs)
        Rail(RailKind.Gap, MaterialTheme.colorScheme.outlineVariant, live = false, Modifier.width(40.dp).fillMaxHeight())
        Text(
            stringResource(R.string.timeline_gap, duration(gap.toMs - gap.fromMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically),
        )
    }
}

private enum class RailKind { Stay, Move, Gap }

/** Where a rail fades: [In] from faint at its top to full at its bottom, [Out] the other way round. */
private enum class RailFade { None, In, Out }

/**
 * An open end runs to the edge of the row, to join the rail of the next one instead of ending in a cap;
 * [fade] lets it fade away towards the other end.
 */
@Composable
private fun Rail(
    kind: RailKind,
    color: Color,
    live: Boolean,
    modifier: Modifier = Modifier,
    openTop: Boolean = false,
    openBottom: Boolean = false,
    fade: RailFade = RailFade.None,
) {
    val context = LocalContext.current
    // The newest stay or move breathes while tracking runs: the one moving part of the screen.
    val animate = live && remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "live")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    } else {
        1f
    }
    Canvas(modifier = modifier) {
        val x = size.width / 2
        when (kind) {
            RailKind.Stay -> {
                val w = 10.dp.toPx()
                // An open end reaches past the edge, so its rounded cap is cut off there.
                val top = if (openTop) -w else 6.dp.toPx()
                val bottom = if (openBottom) size.height + w else size.height - 6.dp.toPx()
                // The halo goes first, so the block stays crisp on top of it.
                if (live) drawCircle(color.copy(alpha = 0.35f * pulse), radius = 11.dp.toPx() * (0.7f + 0.3f * pulse), center = Offset(x, bottom - w / 2))
                val brush = when (fade) {
                    RailFade.None -> SolidColor(color)
                    RailFade.In -> Brush.verticalGradient(listOf(color.copy(alpha = OTHER_DAY_ALPHA), color), startY = 6.dp.toPx(), endY = size.height)
                    RailFade.Out -> Brush.verticalGradient(listOf(color, color.copy(alpha = OTHER_DAY_ALPHA)), startY = 0f, endY = size.height - 6.dp.toPx())
                }
                clipRect {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(x - w / 2, top),
                        size = Size(w, bottom - top),
                        cornerRadius = CornerRadius(w / 2),
                    )
                }
            }
            RailKind.Move -> {
                val step = 8.dp.toPx()
                val r = 2.2.dp.toPx()
                // Dots are counted from an open end, half a step from its edge, so they line up with the dots of the
                // row on the other side: one step apart across the edge, as everywhere else.
                val first = 4.dp.toPx() + r
                val ys = when {
                    openTop -> generateSequence(step / 2) { it + step }.takeWhile { it < (if (openBottom) size.height else size.height - r) }
                    openBottom -> generateSequence(size.height - step / 2) { it - step }.takeWhile { it >= first }
                    else -> generateSequence(first) { it + step }.takeWhile { it < size.height - r }
                }
                ys.forEach { y ->
                    val share = y / size.height
                    val alpha = when (fade) {
                        RailFade.None -> 1f
                        RailFade.In -> OTHER_DAY_ALPHA + (1 - OTHER_DAY_ALPHA) * share
                        RailFade.Out -> 1 - (1 - OTHER_DAY_ALPHA) * share
                    }
                    drawCircle(color.copy(alpha = color.alpha * alpha), radius = r, center = Offset(x, y))
                }
                if (live) drawCircle(color.copy(alpha = pulse), radius = 5.dp.toPx(), center = Offset(x, size.height - 8.dp.toPx()))
            }
            RailKind.Gap -> drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx())),
            )
        }
    }
}
