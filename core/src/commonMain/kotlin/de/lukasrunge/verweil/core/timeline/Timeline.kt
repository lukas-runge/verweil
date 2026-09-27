package de.lukasrunge.verweil.core.timeline

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

/** Entries that finish this close before Dawarich's last one count as already covered by Dawarich. */
private val OVERLAP_TOLERANCE_MS = 2.minutes.inWholeMilliseconds

/**
 * The day as shown in the app: Dawarich's timeline, followed by what this phone recognised after Dawarich's last
 * entry. That tail is what has not been uploaded yet, or what Dawarich has not turned into tracks and visits yet,
 * such as the ongoing stay. An ongoing stay that Dawarich already shows continues Dawarich's entry, with its name.
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
        result += segment.toEntry(startMs = maxOf(segment.startMs, minOf(coveredUntil, segment.endMs)))
    }
    return result
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
