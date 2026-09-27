package de.lukasrunge.verweil.core.engine

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayHeartbeat
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.TrackPoint
import de.lukasrunge.verweil.core.place.InMemoryPlaceStore
import de.lukasrunge.verweil.core.place.PlaceMemory
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

        val out = s.runToEnd()

        assertTrue(out.none { it is StayStarted })
        val distance = out.dawarichDistanceMeters()
        assertTrue(distance in 800.0..840.0, "walked about 833 m, got $distance")
        val points = out.filterIsInstance<TrackPoint>()
        points.zipWithNext { a, b -> assertTrue(distanceMeters(a.fix.point, b.fix.point) >= 15.0) }
    }

    @Test
    fun smoothingKeepsANoisyWalkAtItsTrueLength() {
        fun walk(config: EngineConfig): Double {
            val s = Scenario()
            val random = Random(3)
            s.activity(Activity.WALKING)
            // Five minutes east at 1.4 m/s, a fix every second with 4 m of jitter: 420 m.
            repeat(300) { i ->
                s.fix(i * 1.4 + random.gaussian() * 4, random.gaussian() * 4, accuracy = 6.0, speed = 1.4, bearing = 90.0)
                s.advance(1.seconds)
            }
            return s.runToEnd(StayEngine(config)).dawarichDistanceMeters()
        }

        val everyFix = walk(EngineConfig(smoothTrack = false, simplifyToleranceM = null, minPointSpacingM = 0.0))
        val smoothed = walk(EngineConfig(smoothTrack = true))

        assertTrue(everyFix > 420 * 1.3, "the jitter alone adds distance, got $everyFix m")
        assertTrue(smoothed in 400.0..440.0, "walked 420 m, got $smoothed m")
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

        val out = s.runToEnd()

        val ended = out.filterIsInstance<StayEnded>().single()
        assertEquals(stillSince, ended.sinceMs)
        assertEquals(lastPresence, ended.untilMs)
        val afterStay = out.dropWhile { it !is StayEnded }
        val distance = afterStay.dawarichDistanceMeters()
        assertTrue(distance in 380.0..430.0, "walked about 413 m after the stay, got $distance")
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

        val out = s.runToEnd()

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

    @Test
    fun longStayGetsHourlyHeartbeatsAtTheAnchor() {
        val s = Scenario()
        walkEast(s, fromM = 0.0, minutes = 2)
        s.activity(Activity.STILL)
        stayAt(s, eastM = 168.0, minutes = 180)

        val out = s.run()

        val anchor = out.filterIsInstance<StayStarted>().single().anchor
        val heartbeats = out.filterIsInstance<StayHeartbeat>()
        assertEquals(3, heartbeats.size)
        assertTrue(heartbeats.all { it.anchor == anchor })
        assertTrue(out.dawarichDistanceMeters() < 180.0, "heartbeats add no distance")
    }

    @Test
    fun stoppingClosesAnOpenStayAtTheLastEvidence() {
        val s = Scenario()
        s.activity(Activity.STILL)
        val stillSince = s.nowMs
        stayAt(s, eastM = 0.0, minutes = 30)
        val lastFix = s.nowMs
        s.advance(10.minutes)
        s.tick()
        val engine = StayEngine()
        s.run(engine)

        val ended = engine.finish().filterIsInstance<StayEnded>().single()

        assertEquals(stillSince, ended.sinceMs)
        assertEquals(lastFix, ended.untilMs)
        assertEquals(Mode.MOVING, engine.mode)
        assertNull(engine.stayAnchor)
    }

    @Test
    fun aLateStaleEventDoesNotShortenTheStay() {
        val s = Scenario()
        s.activity(Activity.STILL)
        val stillSince = s.nowMs
        stayAt(s, eastM = 0.0, minutes = 30)
        val lastFix = s.nowMs
        // Play Services repeats the current activity with its original time when transitions are registered again.
        s.events += ActivityChange(stillSince, Activity.STILL)
        val engine = StayEngine()
        s.run(engine)

        assertEquals(lastFix, engine.finish().filterIsInstance<StayEnded>().single().untilMs)
    }

    @Test
    fun aSavedStateContinuesWhereTheEngineStopped() {
        val s = Scenario()
        walkEast(s, fromM = 0.0, minutes = 2)
        s.activity(Activity.STILL)
        stayAt(s, eastM = 168.0, minutes = 20)
        val first = StayEngine()
        val beforeRestart = s.run(first)
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 168.0, minutes = 5)

        val saved = Json.encodeToString(EngineState.serializer(), first.state)
        val afterRestart = s.run(StayEngine(initialState = Json.decodeFromString(EngineState.serializer(), saved)))

        val uninterrupted = StayEngine().let { engine -> s.events.flatMap { engine.process(it) } }
        assertEquals(uninterrupted, beforeRestart + afterRestart)
        assertEquals(1, uninterrupted.filterIsInstance<StayEnded>().size)
    }

    @Test
    fun theVisitUsesAllFixesOfTheStayNotOnlyTheFirstMinutes() {
        val s = Scenario()
        s.activity(Activity.STILL)
        // The first minutes are off by 40 m, then better fixes arrive.
        stayAt(s, eastM = 40.0, minutes = 6, accuracy = 30.0)
        stayAt(s, eastM = 0.0, minutes = 120, accuracy = 10.0)
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 0.0, minutes = 3)

        val ended = s.run().filterIsInstance<StayEnded>().single()

        assertTrue(distanceMeters(ended.anchor, s.at(0.0, 0.0)) > 30.0)
        assertTrue(distanceMeters(ended.center, s.at(0.0, 0.0)) < 5.0)
    }

    @Test
    fun aGeofenceExitOnlyStartsLookingForADeparture() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stayAt(s, eastM = 0.0, minutes = 20)
        s.geofenceExit()
        val engine = StayEngine()
        s.run(engine)
        assertEquals(Mode.LEAVING, engine.mode)

        s.advance(4.minutes)
        s.tick()
        s.run(engine)

        assertEquals(Mode.STAYING, engine.mode)
    }

    @Test
    fun aDifferentWifiStartsLookingForADepartureButNoWifiDoesNot() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stayAt(s, eastM = 0.0, minutes = 20, wifi = arrayOf("a", "b", "c"))
        val engine = StayEngine()
        s.wifi()
        s.run(engine)
        assertEquals(Mode.STAYING, engine.mode)

        s.wifi("x", "y", "z")
        s.run(engine)

        assertEquals(Mode.LEAVING, engine.mode)
    }

    @Test
    fun aKnownPlaceIsRecognisedByItsWifi() {
        val places = PlaceMemory(InMemoryPlaceStore(), EngineConfig())
        val firstDay = Scenario()
        firstDay.activity(Activity.STILL)
        stayAt(firstDay, eastM = 0.0, minutes = 60, wifi = arrayOf("a", "b", "c", "d"))
        firstDay.activity(Activity.WALKING)
        walkEast(firstDay, fromM = 0.0, minutes = 3)
        val firstVisit = firstDay.run(StayEngine(places = places)).filterIsInstance<StayEnded>().single()

        // Next day the fixes are 40 m off, but the Wi-Fi is the same.
        val nextDay = Scenario()
        nextDay.activity(Activity.STILL)
        stayAt(nextDay, eastM = 40.0, minutes = 60, wifi = arrayOf("a", "b", "c", "d"))
        val stay = nextDay.run(StayEngine(places = places)).filterIsInstance<StayStarted>().single()

        assertEquals(firstVisit.center, stay.anchor)
    }

    @Test
    fun aKnownWifiFarAwayIsNotTheSamePlace() {
        val places = PlaceMemory(InMemoryPlaceStore(), EngineConfig())
        // A hotspot on a train: same access points, different places.
        val first = Scenario()
        first.activity(Activity.STILL)
        stayAt(first, eastM = 0.0, minutes = 30, wifi = arrayOf("train"))
        StayEngine(places = places).let { engine -> first.run(engine) + engine.finish() }

        val second = Scenario()
        second.activity(Activity.STILL)
        stayAt(second, eastM = 2_000.0, minutes = 30, wifi = arrayOf("train"))
        val stay = second.run(StayEngine(places = places)).filterIsInstance<StayStarted>().single()

        assertTrue(distanceMeters(stay.anchor, second.at(2_000.0, 0.0)) < 5.0)
    }

    /** Stays put with a fix every minute, and a Wi-Fi scan every ten when [wifi] is given. */
    private fun stayAt(s: Scenario, eastM: Double = 0.0, minutes: Int, accuracy: Double = 10.0, wifi: Array<String>? = null) {
        repeat(minutes) {
            s.advance(1.minutes)
            s.fix(eastM = eastM, northM = 0.0, accuracy = accuracy)
            if (wifi != null && it % 10 == 0) s.wifi(*wifi)
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

    /** Box–Muller; kotlin.random has no normal distribution. */
    private fun Random.gaussian(): Double = sqrt(-2 * ln(1 - nextDouble())) * cos(2 * PI * nextDouble())

    private fun walkEast(s: Scenario, fromM: Double, minutes: Int) {
        s.activity(Activity.WALKING)
        repeat(minutes * 60 / 5) { i ->
            s.fix(eastM = fromM + i * 7.0, northM = 0.0)
            s.advance(5.seconds)
        }
    }
}
