package de.lukasrunge.verweil.core.track

import de.lukasrunge.verweil.core.engine.Scenario
import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.Fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackSimplifierTest {

    private val grid = Scenario()

    @Test
    fun aStraightLineKeepsOnlyAPointEveryMaxInterval() {
        val simplifier = TrackSimplifier()
        val kept = (0 until 100).flatMap { simplifier.add(sample(it, it * 1.4, 0.0), TOLERANCE, SPACING, MAX_INTERVAL_MS) } +
            simplifier.flush()

        assertEquals(listOf(0L, 30L, 60L, 90L, 99L), kept.map { it.fix.timeMs / 1_000 })
    }

    @Test
    fun aCornerIsKept() {
        val simplifier = TrackSimplifier()
        val east = (0..20).map { sample(it, it * 1.4, 0.0) }
        val north = (1..20).map { sample(20 + it, 28.0, it * 1.4) }
        val kept = (east + north).flatMap { simplifier.add(it, TOLERANCE, SPACING, MAX_INTERVAL_MS) } + simplifier.flush()

        val corner = grid.at(28.0, 0.0)
        assertTrue(kept.any { distanceMeters(it.fix.point, corner) < 3.0 }, "kept ${kept.map { it.fix.timeMs }}")
        assertEquals(3, kept.size)
    }

    @Test
    fun jitterWithinTheToleranceIsDropped() {
        val simplifier = TrackSimplifier()
        val kept = (0 until 30).flatMap {
            simplifier.add(sample(it, it * 1.4, if (it % 2 == 0) 1.0 else -1.0), TOLERANCE, SPACING, MAX_INTERVAL_MS)
        } + simplifier.flush()

        assertEquals(2, kept.size)
    }

    @Test
    fun withoutAToleranceItKeepsAPointEverySpacing() {
        val simplifier = TrackSimplifier()
        val kept = (0 until 30).flatMap { simplifier.add(sample(it, it * 5.0, 0.0), null, SPACING, MAX_INTERVAL_MS) }

        // The next 5 m step at or beyond 15 m.
        kept.zipWithNext { a, b -> assertTrue(distanceMeters(a.fix.point, b.fix.point) in 15.0..20.0) }
        assertTrue(kept.size >= 7)
    }

    @Test
    fun aTrackStartingAtAnAnchorDoesNotRepeatIt() {
        val simplifier = TrackSimplifier()
        simplifier.startAt(sample(0, 0.0, 0.0))

        val kept = (1..10).flatMap { simplifier.add(sample(it, it * 1.4, 0.0), TOLERANCE, SPACING, MAX_INTERVAL_MS) } +
            simplifier.flush()

        assertEquals(listOf(10L), kept.map { it.fix.timeMs / 1_000 })
    }

    private fun sample(second: Int, eastM: Double, northM: Double): TrackSample {
        val p = grid.at(eastM, northM)
        return TrackSample(Fix(second * 1_000L, p.lat, p.lon, accuracy = 3.0), Activity.WALKING)
    }

    private companion object {
        const val TOLERANCE = 3.0
        const val SPACING = 15.0
        const val MAX_INTERVAL_MS = 30_000L
    }
}
