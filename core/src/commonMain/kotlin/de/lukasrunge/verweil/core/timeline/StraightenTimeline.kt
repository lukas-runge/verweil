package de.lukasrunge.verweil.core.timeline

/** A stretch of a Dawarich track with one mode of travel; [TravelMode.UNKNOWN] where it stood still. */
data class TrackSegment(val startMs: Long, val endMs: Long, val distanceM: Double, val mode: TravelMode)

/** A leg shorter than this between two stays is noise at their edges, not a move. */
private const val MIN_LEG_MS = 60_000L

/**
 * Dawarich's day without overlaps. Dawarich finds tracks and visits independently and cuts a track only at a gap
 * in the points, never at a visit, so its tracks run into the stays around them and right through short ones.
 *
 * - A move ends where the stay after it begins and starts where the stay before it ends.
 * - A move around a stay becomes one leg before and one after it, each with the distance and mode of the parts of
 *   its track in that time ([segments] by track id), or a share of the distance by time without them.
 * - A move within a stay is left out.
 */
fun straightenTimeline(entries: List<TimelineEntry>, segments: Map<Long, List<TrackSegment>> = emptyMap()): List<TimelineEntry> {
    val stays = entries.filterIsInstance<TimelineEntry.Stay>().sortedBy { it.startMs }
    val moves = entries.filterIsInstance<TimelineEntry.Move>().flatMap { it.legs(stays, it.trackId?.let(segments::get)) }
    return (stays + moves).sortedBy { it.startMs }
}

/** The tracks whose moves are split around a stay: only for these does [straightenTimeline] need segments. */
fun List<TimelineEntry>.tracksToSplit(): List<Long> {
    val stays = filterIsInstance<TimelineEntry.Stay>()
    return filterIsInstance<TimelineEntry.Move>()
        .filter { move -> val (from, to) = move.trimmed(stays); stays.any { it.within(from, to) } }
        .mapNotNull { it.trackId }
}

private fun TimelineEntry.Stay.within(fromMs: Long, toMs: Long) = startMs > fromMs && endMs < toMs

/** The move's time without what the stays at its ends already cover. */
private fun TimelineEntry.Move.trimmed(stays: List<TimelineEntry.Stay>): Pair<Long, Long> {
    val from = stays.filter { it.startMs <= startMs && it.endMs > startMs }.maxOfOrNull { it.endMs } ?: startMs
    val to = stays.filter { it.startMs < endMs && it.endMs >= endMs }.minOfOrNull { it.startMs } ?: endMs
    return from to to
}

private fun TimelineEntry.Move.legs(stays: List<TimelineEntry.Stay>, segments: List<TrackSegment>?): List<TimelineEntry.Move> {
    val (from, to) = trimmed(stays)
    if (to - from < MIN_LEG_MS) return emptyList()
    val inside = stays.filter { it.within(from, to) }
    if (inside.isEmpty() && segments == null) return listOf(copy(startMs = from, endMs = to))
    val bounds = buildList {
        var legStart = from
        inside.forEach { stay ->
            add(legStart to stay.startMs)
            legStart = maxOf(legStart, stay.endMs)
        }
        add(legStart to to)
    }.filter { (a, b) -> b - a >= MIN_LEG_MS }
    val movingMs = bounds.sumOf { (a, b) -> b - a }.coerceAtLeast(1)
    return bounds.map { (a, b) ->
        copy(
            startMs = a,
            endMs = b,
            // Without segments: a share by time, trimmed edges included, since they are where nobody moved.
            distanceM = segments?.distanceBetween(a, b) ?: (distanceM * (b - a) / movingMs),
            mode = segments?.modeBetween(a, b) ?: mode,
        )
    }
}

private fun TrackSegment.overlapShare(fromMs: Long, toMs: Long): Double {
    val overlap = minOf(endMs, toMs) - maxOf(startMs, fromMs)
    return if (overlap <= 0) 0.0 else overlap.toDouble() / maxOf(endMs - startMs, 1)
}

private fun List<TrackSegment>.distanceBetween(fromMs: Long, toMs: Long): Double = sumOf { it.distanceM * it.overlapShare(fromMs, toMs) }

/** The mode most of the distance between the two times had; null where the track did not move. */
private fun List<TrackSegment>.modeBetween(fromMs: Long, toMs: Long): TravelMode? =
    filter { it.mode != TravelMode.UNKNOWN }
        .groupBy { it.mode }
        .mapValues { (_, parts) -> parts.sumOf { it.distanceM * it.overlapShare(fromMs, toMs) } }
        .filterValues { it > 0 }
        .maxByOrNull { it.value }?.key
