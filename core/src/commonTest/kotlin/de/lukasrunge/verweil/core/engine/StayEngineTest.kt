package de.lukasrunge.verweil.core.engine

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.TrackPoint
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StayEngineTest {

    @Test
    fun walkingProducesASpacedTrackAndNoStay() {
        val s = Scenario()
        s.activity(Activity.WALKING)
        repeat(120) { i ->
            s.fix(eastM = i * 7.0, northM = 0.0)
            s.advance(5.seconds)
        }

        val out = s.run()

        assertTrue(out.none { it is StayStarted })
        val distance = out.dawarichDistanceMeters()
        assertTrue(distance in 800.0..840.0, "walked about 833 m, got $distance")
        val points = out.filterIsInstance<TrackPoint>()
        points.zipWithNext { a, b -> assertTrue(distanceMeters(a.fix.point, b.fix.point) >= 15.0) }
    }

    @Test
    fun indoorJitterWhileStillYieldsOneStayAndNoDistance() {
        val s = Scenario()
        walkEast(s, fromM = 0.0, minutes = 5)
        val arrivedAtM = 5 * 60 / 5 * 7.0
        s.activity(Activity.STILL)
        val stillSince = s.nowMs

        val random = Random(42)
        repeat((2.hours / 30.seconds).toInt()) { i ->
            s.advance(30.seconds)
            when {
                // Occasional confident outlier, like a fix snapped to a cell tower.
                i % 37 == 0 -> s.fix(arrivedAtM + 250, 40.0, accuracy = 30.0)
                random.nextDouble() < 0.3 -> s.fix(
                    arrivedAtM + random.nextDouble(-50.0, 50.0),
                    random.nextDouble(-50.0, 50.0),
                    accuracy = random.nextDouble(15.0, 35.0),
                )
                else -> s.fix(
                    arrivedAtM + random.nextDouble(-400.0, 400.0),
                    random.nextDouble(-400.0, 400.0),
                    accuracy = random.nextDouble(60.0, 300.0),
                )
            }
        }

        val out = s.run()

        val stays = out.filterIsInstance<StayStarted>()
        assertEquals(1, stays.size)
        assertEquals(stillSince, stays.single().sinceMs)
        assertTrue(out.none { it is StayEnded })
        assertTrue(out.filterIsInstance<TrackPoint>().none { it.fix.timeMs > stillSince })
        val distance = out.dawarichDistanceMeters()
        assertTrue(distance < arrivedAtM + 20, "only the walk may count, got $distance m")
    }

    @Test
    fun departureNeedsMotionAndAFixOutsideTheExitRadius() {
        val s = Scenario()
        walkEast(s, fromM = 0.0, minutes = 2)
        s.activity(Activity.STILL)
        val stillSince = s.nowMs
        repeat(20) {
            s.advance(1.minutes)
            s.fix(eastM = 168.0 + it % 3, northM = 0.0, accuracy = 25.0)
        }
        val lastPresence = s.nowMs
        s.advance(10.seconds)
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 168.0, minutes = 5)

        val out = s.run()

        val ended = out.filterIsInstance<StayEnded>().single()
        assertEquals(stillSince, ended.sinceMs)
        assertEquals(lastPresence, ended.untilMs)
        val afterStay = out.dropWhile { it !is StayEnded }.drop(1)
        assertTrue(afterStay.filterIsInstance<TrackPoint>().size > 10)
    }

    @Test
    fun aWalkAroundTheBlockWithPreciseFixesIsATrip() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stayAt(s, minutes = 20)
        s.activity(Activity.WALKING)
        walkAroundTheBlock(s, accuracy = 5.0)
        s.activity(Activity.STILL)
        stayAt(s, minutes = 10)

        val out = s.run()

        assertEquals(1, out.filterIsInstance<StayEnded>().size)
        assertEquals(2, out.filterIsInstance<StayStarted>().size)
        val distance = out.dawarichDistanceMeters()
        assertTrue(distance in 280.0..340.0, "walked the 320 m loop, got $distance")
    }

    @Test
    fun theSameWalkWithImpreciseFixesStaysWithinTheStay() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stayAt(s, minutes = 20)
        s.activity(Activity.WALKING)
        walkAroundTheBlock(s, accuracy = 30.0)
        s.activity(Activity.STILL)
        stayAt(s, minutes = 10)

        val out = s.run()

        assertEquals(1, out.filterIsInstance<StayStarted>().size)
        assertTrue(out.none { it is StayEnded || it is TrackPoint })
    }

    @Test
    fun shortStopDoesNotBecomeAStay() {
        val s = Scenario()
        walkEast(s, fromM = 0.0, minutes = 2)
        s.activity(Activity.STILL)
        repeat(4) {
            s.advance(30.seconds)
            s.fix(eastM = 168.0, northM = 0.0)
        }
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 168.0, minutes = 2)

        val out = s.run()

        assertTrue(out.none { it is StayStarted })
        val distance = out.dawarichDistanceMeters()
        assertTrue(distance in 300.0..340.0, "walked about 336 m, got $distance")
    }

    @Test
    fun inaccurateFixesAreNeverForwarded() {
        val s = Scenario()
        s.activity(Activity.WALKING)
        repeat(60) { i ->
            s.fix(eastM = i * 7.0, northM = 0.0)
            s.fix(eastM = i * 7.0 + 300, northM = 200.0, accuracy = 150.0)
            s.advance(5.seconds)
        }

        val out = s.run()

        assertTrue(out.filterIsInstance<TrackPoint>().all { it.fix.accuracy <= 35.0 })
    }

    /** Stays put at the origin with a fix every minute. */
    private fun stayAt(s: Scenario, minutes: Int) {
        repeat(minutes) {
            s.advance(1.minutes)
            s.fix(eastM = 0.0, northM = 0.0, accuracy = 10.0)
        }
    }

    /** A 100 × 60 m loop at walking pace that starts and ends at the origin, never farther than 117 m from it. */
    private fun walkAroundTheBlock(s: Scenario, accuracy: Double) {
        val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0, 0.0 to 0.0)
        corners.zipWithNext { (fromEast, fromNorth), (toEast, toNorth) ->
            val steps = (hypot(toEast - fromEast, toNorth - fromNorth) / 7.0).roundToInt()
            repeat(steps) { i ->
                val f = (i + 1.0) / steps
                s.advance(5.seconds)
                s.fix(fromEast + (toEast - fromEast) * f, fromNorth + (toNorth - fromNorth) * f, accuracy)
            }
        }
    }

    private fun walkEast(s: Scenario, fromM: Double, minutes: Int) {
        s.activity(Activity.WALKING)
        repeat(minutes * 60 / 5) { i ->
            s.fix(eastM = fromM + i * 7.0, northM = 0.0)
            s.advance(5.seconds)
        }
    }
}
