package de.lukasrunge.verweil.core.track

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.Fix
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** A track fix with the activity at the time it was taken. */
@Serializable
data class TrackSample(val fix: Fix, val activity: Activity)

/**
 * Keeps the points that shape a track and drops those on the line between them.
 *
 * Streaming variant of Douglas–Peucker ("opening window"): a segment grows from the last kept point
 * while every point since lies within the tolerance of it; the point before the first one outside
 * becomes the next kept point. Needs smooth input, otherwise every jitter spike counts as a corner.
 */
@Serializable
class TrackSimplifier(
    /** The last point handed out, or where the track starts; the next segment begins here. */
    private var anchor: TrackSample? = null,
    /** Points since the anchor, all within the tolerance of the segment to the newest one. */
    private val window: MutableList<TrackSample> = mutableListOf(),
) {
    /**
     * Adds [sample] and returns the points that became final.
     * With [toleranceM] null it keeps a point every [minSpacingM] instead, the way tracks were thinned before.
     * [maxIntervalMs] bounds the time between points, so a straight line still shows its speed.
     */
    fun add(sample: TrackSample, toleranceM: Double?, minSpacingM: Double, maxIntervalMs: Long): List<TrackSample> {
        val start = anchor ?: return emit(sample)
        if (toleranceM == null) {
            return if (distanceMeters(start.fix.point, sample.fix.point) >= minSpacingM) emit(sample) else emptyList()
        }
        val out = mutableListOf<TrackSample>()
        if (window.any { offset(start.fix, sample.fix, it.fix) > toleranceM }) {
            out += emit(window.last())
        }
        window += sample
        if (sample.fix.timeMs - anchor!!.fix.timeMs >= maxIntervalMs) out += emit(sample)
        return out
    }

    /** Starts a track at [origin] without handing it out, e.g. at a stay anchor that is already a point. */
    fun startAt(origin: TrackSample) {
        anchor = origin
        window.clear()
    }

    /** The track pauses or ends: hands out the newest point, so the track reaches it. */
    fun flush(): List<TrackSample> = if (window.isEmpty()) emptyList() else emit(window.last())

    private fun emit(sample: TrackSample): List<TrackSample> {
        anchor = sample
        window.clear()
        return listOf(sample)
    }

    /** Distance of [p] from the segment [a]–[b], on a flat grid around [a]; fine for the length of a window. */
    private fun offset(a: Fix, b: Fix, p: Fix): Double {
        val scale = cos(a.lat * PI / 180)
        fun x(f: Fix) = (f.lon - a.lon) * METERS_PER_DEGREE * scale
        fun y(f: Fix) = (f.lat - a.lat) * METERS_PER_DEGREE
        val bx = x(b)
        val by = y(b)
        val px = x(p)
        val py = y(p)
        val length2 = bx * bx + by * by
        val t = if (length2 == 0.0) 0.0 else ((px * bx + py * by) / length2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
