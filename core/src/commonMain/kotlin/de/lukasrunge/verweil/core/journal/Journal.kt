package de.lukasrunge.verweil.core.journal

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.lukasrunge.verweil.core.db.Journal_segment
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayHeartbeat
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.TrackPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

enum class SegmentKind { STAY, MOVE }

/** One entry of the timeline: a stay, or a move between two stays. */
data class Segment(
    val id: Long,
    val kind: SegmentKind,
    val startMs: Long,
    /** For an ongoing stay the latest evidence of presence, for a move its latest point. */
    val endMs: Long,
    val ongoing: Boolean,
    /** A stay's centre; a move's latest point. */
    val point: GeoPoint,
    val distanceM: Double,
    /** A move's distance per recognised activity. */
    val activityMeters: Map<Activity, Double>,
    val placeName: String?,
) {
    /** The activity that covered most of a move's distance, if recognised. */
    val activity: Activity? get() = activityMeters.maxByOrNull { it.value }?.key
}

/**
 * The engine's decisions as a timeline of stays and moves, for the app to show.
 * Distance adds up the uploaded points like Dawarich does, so the app and Dawarich agree.
 */
class Journal(database: VerweilDatabase) {
    private val queries = database.journalQueries

    /** Adds what the engine decided. Runs inside the tracker's transaction. */
    fun record(outputs: List<EngineOutput>) = outputs.forEach { output ->
        when (output) {
            is TrackPoint -> onTrackPoint(output)
            is StayStarted -> onStayStarted(output)
            is StayHeartbeat -> latestOngoing(SegmentKind.STAY)?.let {
                update(it.copy(endMs = maxOf(it.endMs, output.timeMs)))
            }
            is StayEnded -> onStayEnded(output)
        }
    }

    /** Tracking stopped: nothing is ongoing any more. */
    fun endOngoing() = queries.endOngoing()

    /** Tracking paused during a stay: it ends for now at its last evidence, [untilMs]. */
    fun pauseStay(untilMs: Long) {
        latestOngoing(SegmentKind.STAY)?.let { update(it.copy(endMs = maxOf(it.endMs, untilMs), ongoing = false)) }
        queries.endOngoing()
    }

    /** Tracking resumed and the paused stay goes on; missing, e.g. from before the journal existed, it is added. */
    fun resumeStay(anchor: GeoPoint, sinceMs: Long) {
        val stay = latest()?.takeIf { it.kind == SegmentKind.STAY && it.startMs == sinceMs }
        if (stay == null) record(listOf(StayStarted(anchor, sinceMs))) else update(stay.copy(ongoing = true))
    }

    /** The newest segment, e.g. the ongoing stay. */
    fun latest(): Segment? = queries.latest().executeAsOneOrNull()?.toSegment()

    fun latestFlow(context: CoroutineContext): Flow<Segment?> =
        queries.latest().asFlow().mapToOneOrNull(context).map { it?.toSegment() }

    /** Segments that overlap the time range, oldest first. */
    fun segments(fromMs: Long, toMs: Long): List<Segment> =
        queries.overlapping(fromMs, toMs).executeAsList().map { it.toSegment() }

    fun segmentsFlow(fromMs: Long, toMs: Long, context: CoroutineContext): Flow<List<Segment>> =
        queries.overlapping(fromMs, toMs).asFlow().mapToList(context).map { rows -> rows.map { it.toSegment() } }

    fun setPlaceName(id: Long, name: String) = queries.setPlaceName(name, id)

    /** Forgets finished segments that ended before [beforeMs]. */
    fun prune(beforeMs: Long) = queries.pruneBefore(beforeMs)

    fun clear() = queries.deleteAll()

    private fun onTrackPoint(output: TrackPoint) {
        val fix = output.fix
        val latest = latest()
        if (latest != null && latest.ongoing && latest.kind == SegmentKind.MOVE && fix.timeMs - latest.endMs <= MAX_GAP_MS) {
            val step = distanceMeters(latest.point, fix.point)
            update(
                latest.copy(
                    endMs = maxOf(latest.endMs, fix.timeMs),
                    point = fix.point,
                    distanceM = latest.distanceM + step,
                    activityMeters = latest.activityMeters.plusDistance(output.activity, step),
                ),
            )
            return
        }
        queries.endOngoing()
        // A move that leaves a stay starts where and when the stay ended, like the track uploaded to Dawarich.
        val origin = latest?.takeIf { it.kind == SegmentKind.STAY && fix.timeMs - it.endMs <= MAX_GAP_MS }
        val step = origin?.let { distanceMeters(it.point, fix.point) } ?: 0.0
        insert(
            SegmentKind.MOVE,
            startMs = origin?.endMs ?: fix.timeMs,
            endMs = fix.timeMs,
            point = fix.point,
            distanceM = step,
            activities = emptyMap<Activity, Double>().plusDistance(output.activity, step),
        )
    }

    private fun onStayStarted(output: StayStarted) {
        latestOngoing(SegmentKind.MOVE)?.let { move ->
            // The stay is backdated to when it most likely began; the move ends there, at the stay.
            val step = distanceMeters(move.point, output.anchor)
            update(
                move.copy(
                    endMs = output.sinceMs.coerceIn(move.startMs, move.endMs),
                    ongoing = false,
                    distanceM = move.distanceM + step,
                ),
            )
        }
        queries.endOngoing()
        insert(SegmentKind.STAY, output.sinceMs, output.sinceMs, output.anchor, distanceM = 0.0, activities = emptyMap())
    }

    private fun onStayEnded(output: StayEnded) {
        // Ongoing, or paused when tracking stopped.
        val stay = latestOngoing(SegmentKind.STAY)
            ?: queries.stayStartedAt(output.sinceMs).executeAsOneOrNull()?.toSegment()
        if (stay == null) {
            // The stay began before this journal existed, e.g. before an app update.
            insert(SegmentKind.STAY, output.sinceMs, output.untilMs, output.center, distanceM = 0.0, activities = emptyMap())
            queries.endOngoing()
            return
        }
        update(stay.copy(endMs = output.untilMs, ongoing = false, point = output.center))
    }

    private fun latestOngoing(kind: SegmentKind): Segment? =
        latest()?.takeIf { it.ongoing && it.kind == kind }

    private fun insert(
        kind: SegmentKind,
        startMs: Long,
        endMs: Long,
        point: GeoPoint,
        distanceM: Double,
        activities: Map<Activity, Double>,
    ) = queries.insert(kind.key, startMs, endMs, 1, point.lat, point.lon, distanceM, encodeActivities(activities))

    private fun update(segment: Segment) = queries.update(
        endMs = segment.endMs,
        ongoing = if (segment.ongoing) 1 else 0,
        lat = segment.point.lat,
        lon = segment.point.lon,
        distanceM = segment.distanceM,
        activities = encodeActivities(segment.activityMeters),
        id = segment.id,
    )

    private companion object {
        /** Longer without a track point, e.g. while the app was killed, and the next point starts a new move. */
        val MAX_GAP_MS = 15.minutes.inWholeMilliseconds
    }
}

private val SegmentKind.key get() = name.lowercase()

private fun Map<Activity, Double>.plusDistance(activity: Activity, meters: Double): Map<Activity, Double> =
    if (activity == Activity.UNKNOWN || activity == Activity.STILL) this
    else this + (activity to (this[activity] ?: 0.0) + meters)

private fun encodeActivities(activities: Map<Activity, Double>): String =
    activities.entries.joinToString(";") { "${it.key.name}=${it.value}" }

private fun decodeActivities(text: String): Map<Activity, Double> = text.split(';').mapNotNull { entry ->
    val (name, meters) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
    val activity = Activity.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
    activity to (meters.toDoubleOrNull() ?: return@mapNotNull null)
}.toMap()

private fun Journal_segment.toSegment() = Segment(
    id = id,
    kind = SegmentKind.entries.first { it.key == kind },
    startMs = start_ms,
    endMs = end_ms,
    ongoing = ongoing != 0L,
    point = GeoPoint(lat, lon),
    distanceM = distance_m,
    activityMeters = decodeActivities(activities),
    placeName = place_name,
)
