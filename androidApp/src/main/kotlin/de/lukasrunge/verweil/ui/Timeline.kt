package de.lukasrunge.verweil.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.lukasrunge.verweil.R
import de.lukasrunge.verweil.core.journal.Segment
import de.lukasrunge.verweil.core.journal.SegmentKind

/** Longer between two segments, and the timeline shows that nothing was recorded. */
private const val GAP_MS = 20 * 60_000L

private sealed interface Entry {
    data class Item(val segment: Segment) : Entry
    data class Gap(val fromMs: Long, val toMs: Long) : Entry
}

/**
 * The day as a line: a stay is a solid block, a move a dotted path, a gap a faint dashed line.
 * [dayStartMs] clips segments that began the day before, so the first row starts at midnight.
 */
@Composable
fun Timeline(segments: List<Segment>, dayStartMs: Long, nowMs: Long, live: Boolean, modifier: Modifier = Modifier) {
    val entries = remember(segments) {
        buildList {
            segments.forEachIndexed { i, segment ->
                val previous = segments.getOrNull(i - 1)
                if (previous != null && segment.startMs - previous.endMs > GAP_MS) add(Entry.Gap(previous.endMs, segment.startMs))
                add(Entry.Item(segment))
            }
        }
    }
    Column(modifier = modifier) {
        entries.forEachIndexed { i, entry ->
            when (entry) {
                is Entry.Item -> SegmentRow(
                    segment = entry.segment,
                    startMs = maxOf(entry.segment.startMs, dayStartMs),
                    nowMs = nowMs,
                    // Only the newest segment can still be going on, and only while tracking runs.
                    live = live && entry.segment.ongoing && i == entries.lastIndex,
                )
                is Entry.Gap -> GapRow(entry)
            }
        }
    }
}

@Composable
private fun SegmentRow(segment: Segment, startMs: Long, nowMs: Long, live: Boolean) {
    val context = LocalContext.current
    val colors = LocalStateColors.current
    val isStay = segment.kind == SegmentKind.STAY
    val endMs = if (live) nowMs else segment.endMs
    val open = {
        // Any map app; "geo:" with a label pins the exact point.
        val uri = "geo:0,0?q=${segment.point.lat},${segment.point.lon}".toUri()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = if (isStay) 72.dp else 60.dp)
            .clickable(onClick = open)
            .padding(horizontal = 24.dp),
    ) {
        Text(
            time(startMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(52.dp)
                .padding(top = 12.dp),
        )
        Rail(
            kind = if (isStay) RailKind.Stay else RailKind.Move,
            color = if (isStay) colors.staying else colors.moving,
            live = live,
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
            if (isStay) {
                Text(
                    segment.placeName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.timeline_stay),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        live -> stringResource(R.string.timeline_since, duration(endMs - segment.startMs))
                        // Tracking stopped without ending the stay; it goes on when tracking resumes.
                        segment.ongoing -> stringResource(R.string.timeline_open_since, time(segment.startMs))
                        else -> duration(endMs - segment.startMs)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        ImageVector.vectorResource(segment.activity.movingIcon()),
                        contentDescription = null,
                        tint = colors.moving,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 2.dp),
                    )
                    Text(
                        stringResource(segment.activity.movingLabel()),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.timeline_move_detail, distance(segment.distanceM), duration(endMs - segment.startMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GapRow(gap: Entry.Gap) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 44.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("", modifier = Modifier.width(52.dp))
        Rail(RailKind.Gap, MaterialTheme.colorScheme.outlineVariant, live = false, Modifier.width(40.dp).fillMaxHeight())
        Text(
            stringResource(R.string.timeline_gap, duration(gap.toMs - gap.fromMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class RailKind { Stay, Move, Gap }

@Composable
private fun Rail(kind: RailKind, color: Color, live: Boolean, modifier: Modifier = Modifier) {
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
                val top = 6.dp.toPx()
                val bottom = size.height - 6.dp.toPx()
                // The halo goes first, so the block stays crisp on top of it.
                if (live) drawCircle(color.copy(alpha = 0.35f * pulse), radius = 11.dp.toPx() * (0.7f + 0.3f * pulse), center = Offset(x, bottom - w / 2))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x - w / 2, top),
                    size = Size(w, bottom - top),
                    cornerRadius = CornerRadius(w / 2),
                )
            }
            RailKind.Move -> {
                val step = 8.dp.toPx()
                val r = 2.2.dp.toPx()
                var y = 4.dp.toPx() + r
                while (y < size.height - r) {
                    drawCircle(color, radius = r, center = Offset(x, y))
                    y += step
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
