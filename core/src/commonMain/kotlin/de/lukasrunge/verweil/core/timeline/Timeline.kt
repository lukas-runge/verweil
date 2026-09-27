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

    /** What this phone recognised and Dawarich does not show yet. */
    PHONE,
}

enum class TravelMode {
    WALKING, RUNNING, CYCLING, DRIVING, MOTORCYCLE, BUS, TRAIN, FLYING, BOAT,

    /** Car, bus or train: the phone's motion sensors cannot tell them apart. */
    VEHICLE,
    UNKNOWN,
}

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
    ) : TimelineEntry

    @Serializable
    data class Move(
        override val startMs: Long,
        override val endMs: Long,
        val distanceM: Double,
        val mode: TravelMode,
        override val ongoing: Boolean = false,
        override val source: Source = Source.DAWARICH,
    ) : TimelineEntry
}

/** A stay of the phone this close to one Dawarich knows is the same place, and takes Dawarich's name. */
const val SAME_PLACE_M = 100.0

/** Entries that finish this close before Dawarich's last one count as already covered by Dawarich. */
private val OVERLAP_TOLERANCE_MS = 2.minutes.inWholeMilliseconds

/**
 * The day as shown in the app: Dawarich's timeline, followed by what this phone recognised after Dawarich's last
 * entry. That tail is what has not been uploaded yet, or what Dawarich has not turned into tracks and visits yet,
 * such as the ongoing stay. An ongoing stay that Dawarich already shows continues Dawarich's entry, with its name;
 * other stays of the phone take the name Dawarich gives a place nearby.
 */
fun mergeTimeline(dawarich: List<TimelineEntry>, phone: List<Segment>): List<TimelineEntry> {
    val server = dawarich.sortedBy { it.startMs }
    val coveredUntil = server.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE
    val result = server.toMutableList()
    phone.sortedBy { it.startMs }.forEach { segment ->
        if (!segment.ongoing && segment.endMs <= coveredUntil + OVERLAP_TOLERANCE_MS) return@forEach
        val last = result.lastOrNull()
        if (segment.ongoing && segment.kind == SegmentKind.STAY && last is TimelineEntry.Stay && last.endMs >= segment.startMs) {
            result[result.lastIndex] = last.copy(endMs = maxOf(last.endMs, segment.endMs), ongoing = true)
            return@forEach
        }
        result += segment.toEntry(startMs = maxOf(segment.startMs, minOf(coveredUntil, segment.endMs))).namedLike(server)
    }
    return result
}

private fun TimelineEntry.namedLike(server: List<TimelineEntry>): TimelineEntry {
    if (this !is TimelineEntry.Stay || point == null) return this
    return server.nameNear(point)?.let { copy(name = it) } ?: this
}

/** The name Dawarich gives the place at [point], if one of these stays is within [SAME_PLACE_M]. */
fun List<TimelineEntry>.nameNear(point: GeoPoint): String? = filterIsInstance<TimelineEntry.Stay>()
    .filter { it.name != null && it.point != null && distanceMeters(it.point, point) <= SAME_PLACE_M }
    .minByOrNull { distanceMeters(it.point!!, point) }
    ?.name

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

private fun Segment.toEntry(startMs: Long): TimelineEntry = when (kind) {
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
