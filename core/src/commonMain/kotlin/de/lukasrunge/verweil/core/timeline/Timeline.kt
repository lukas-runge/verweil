package de.lukasrunge.verweil.core.timeline

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.journal.Segment
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/** Where an entry of the timeline comes from. */
enum class Source {
    /** Dawarich's own timeline: everything any device sent, with Dawarich's names and tracks. */
    DAWARICH,

    /** What this phone recognised after Dawarich's newest entry: not in Dawarich yet. */
    PHONE,

    /** What this phone recognised in a stretch Dawarich has data around but not for, e.g. a visit Dawarich dropped. */
    PHONE_MISSING,
}

enum class TravelMode {
    WALKING, RUNNING, CYCLING, DRIVING, MOTORCYCLE, BUS, TRAIN, FLYING, BOAT,

    /** Car, bus or train: the phone's motion sensors cannot tell them apart. */
    VEHICLE,
    UNKNOWN,
}

/** A tag the user gave a place in Dawarich; [color] is a hex colour like "#FF5733", [icon] an emoji. */
@Serializable
data class PlaceTag(val name: String, val icon: String? = null, val color: String? = null)

/** One row of the day: a stay, or a move between two stays. */
@Serializable
sealed interface TimelineEntry {
    val startMs: Long
    val endMs: Long

    /** Still going on; only ever the newest entry, and only while tracking runs. */
    val ongoing: Boolean
    val source: Source

    @Serializable
    data class Stay(
        override val startMs: Long,
        override val endMs: Long,
        val point: GeoPoint?,
        val name: String?,
        override val ongoing: Boolean = false,
        override val source: Source = Source.DAWARICH,
        /** Dawarich's visit, e.g. to open it; null for stays only the phone knows. */
        val visitId: Long? = null,
        /** The tags of Dawarich's place, e.g. "Home"; for a stay of the phone those of Dawarich's place nearby. */
        val tags: List<PlaceTag> = emptyList(),
    ) : TimelineEntry

    @Serializable
    data class Move(
        override val startMs: Long,
        override val endMs: Long,
        val distanceM: Double,
        val mode: TravelMode,
        override val ongoing: Boolean = false,
        override val source: Source = Source.DAWARICH,
        /** Dawarich's track, e.g. to correct its mode; null for moves only the phone knows. */
        val trackId: Long? = null,
    ) : TimelineEntry
}

/** A stay of the phone this close to one Dawarich knows is the same place, and takes Dawarich's name. */
const val SAME_PLACE_M = 100.0

/** A phone entry that begins this soon after Dawarich's newest one of the same kind continues it. */
private val CONTINUATION_MS = 2.minutes.inWholeMilliseconds

/** Dawarich shows a phone entry when its own entries of the same kind cover this share of it ... */
private const val COVERED_SHARE = 0.8

/** ... or leave less than this of it out: stays and tracks never start and end on the same minute in both. */
private val UNCOVERED_MS = 10.minutes.inWholeMilliseconds

/**
 * The day as shown in the app: Dawarich's timeline, completed with what this phone recognised and Dawarich
 * does not show.
 *
 * - The phone's newest entry, reaching past everything in Dawarich, continues Dawarich's newest entry when that is
 *   of the same kind and touches it: the ongoing walk Dawarich has tracked up to a few minutes ago, or the stay
 *   Dawarich already shows. A move gains the phone's distance for the time Dawarich has not seen yet, pro rata.
 * - A phone entry that Dawarich's entries of the same kind mostly cover is left out: Dawarich has it.
 * - Anything else is added as the phone saw it: after Dawarich's newest entry as not uploaded yet, before it as
 *   missing in Dawarich.
 *
 * Stays of the phone take the name Dawarich gives a place nearby.
 */
fun mergeTimeline(dawarich: List<TimelineEntry>, phone: List<Segment>): List<TimelineEntry> {
    val result = dawarich.sortedBy { it.startMs }.toMutableList()
    val newestEnd = result.maxOfOrNull { it.endMs }
    phone.sortedBy { it.startMs }.forEach { segment ->
        val entry = segment.toEntry()
        val sameKind = result.filter { it.source == Source.DAWARICH && it.isSameKindAs(entry) }
        // Dawarich's last row, not the one ending last: its visits can run on past a walk it also tracked.
        val newest = result.filter { it.source == Source.DAWARICH }.maxByOrNull { it.startMs }
        if (newest != null && newest.isSameKindAs(entry) && entry.endMs > newest.endMs &&
            entry.startMs <= newest.endMs + CONTINUATION_MS
        ) {
            result[result.indexOf(newest)] = newest.continuedBy(entry)
            return@forEach
        }
        val length = entry.endMs - entry.startMs
        val covered = sameKind.sumOf { it.overlapMs(entry) }
        // An ongoing entry's end is its latest evidence, e.g. the last hourly point of a stay; it is never "short".
        if (!entry.ongoing && (covered >= length * COVERED_SHARE || length - covered < UNCOVERED_MS)) return@forEach
        val source = if (newestEnd == null || entry.startMs >= newestEnd - CONTINUATION_MS) Source.PHONE else Source.PHONE_MISSING
        result += entry.withSource(source).namedLike(dawarich)
    }
    return result.sortedBy { it.startMs }
}

private fun TimelineEntry.isSameKindAs(other: TimelineEntry) = (this is TimelineEntry.Stay) == (other is TimelineEntry.Stay)

private fun TimelineEntry.overlapMs(other: TimelineEntry): Long =
    maxOf(0, minOf(endMs, other.endMs) - maxOf(startMs, other.startMs))

/** Dawarich's entry, running on as long as the phone's does. */
private fun TimelineEntry.continuedBy(phone: TimelineEntry): TimelineEntry = when (this) {
    is TimelineEntry.Stay -> copy(endMs = phone.endMs, ongoing = phone.ongoing)
    is TimelineEntry.Move -> {
        val unseen = (phone.endMs - maxOf(endMs, phone.startMs)).toDouble() / maxOf(phone.endMs - phone.startMs, 1)
        copy(endMs = phone.endMs, ongoing = phone.ongoing, distanceM = distanceM + (phone as TimelineEntry.Move).distanceM * unseen)
    }
}

private fun TimelineEntry.withSource(source: Source): TimelineEntry = when (this) {
    is TimelineEntry.Stay -> copy(source = source)
    is TimelineEntry.Move -> copy(source = source)
}

private fun TimelineEntry.namedLike(server: List<TimelineEntry>): TimelineEntry {
    if (this !is TimelineEntry.Stay || point == null) return this
    return server.placeNear(point)?.let { copy(name = it.name, tags = it.tags) } ?: this
}

/** The name Dawarich gives the place at [point], if one of these stays is within [SAME_PLACE_M]. */
fun List<TimelineEntry>.nameNear(point: GeoPoint): String? = placeNear(point)?.name

/** Dawarich's named stay closest to [point], within [SAME_PLACE_M]. */
private fun List<TimelineEntry>.placeNear(point: GeoPoint): TimelineEntry.Stay? = filterIsInstance<TimelineEntry.Stay>()
    .filter { it.name != null && it.point != null && distanceMeters(it.point, point) <= SAME_PLACE_M }
    .minByOrNull { distanceMeters(it.point!!, point) }

/** How many different places the stays are at: stays within [SAME_PLACE_M] of each other count once. */
fun List<TimelineEntry>.placeCount(): Int {
    val places = mutableListOf<TimelineEntry.Stay>()
    filterIsInstance<TimelineEntry.Stay>().forEach { stay ->
        val same = places.any { other ->
            if (stay.point != null && other.point != null) distanceMeters(stay.point, other.point) <= SAME_PLACE_M
            else stay.name != null && stay.name == other.name
        }
        if (!same) places += stay
    }
    return places.size
}

private fun Segment.toEntry(): TimelineEntry = when (kind) {
    SegmentKind.STAY -> TimelineEntry.Stay(
        startMs = startMs,
        endMs = endMs,
        point = point,
        name = placeName?.takeIf { it.isNotBlank() },
        ongoing = ongoing,
        source = Source.PHONE,
    )
    SegmentKind.MOVE -> TimelineEntry.Move(
        startMs = startMs,
        endMs = endMs,
        distanceM = distanceM,
        mode = activity?.toTravelMode() ?: TravelMode.UNKNOWN,
        ongoing = ongoing,
        source = Source.PHONE,
    )
}

fun Activity.toTravelMode(): TravelMode = when (this) {
    Activity.WALKING -> TravelMode.WALKING
    Activity.RUNNING -> TravelMode.RUNNING
    Activity.CYCLING -> TravelMode.CYCLING
    Activity.VEHICLE -> TravelMode.VEHICLE
    Activity.STILL, Activity.UNKNOWN -> TravelMode.UNKNOWN
}
