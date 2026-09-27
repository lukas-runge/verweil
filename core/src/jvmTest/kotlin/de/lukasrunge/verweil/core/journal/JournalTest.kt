package de.lukasrunge.verweil.core.journal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.Scenario
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.tracking.Tracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class JournalTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VerweilDatabase.Schema.create(it) }
    private val database = VerweilDatabase(driver)
    private val journal = Journal(database)

    @Test
    fun aDayBecomesStaysAndTheWalkBetweenThem() {
        val s = Scenario()
        val tracker = Tracker(database)
        s.activity(Activity.STILL)
        stay(s, at = 0.0, minutes = 20)
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 0.0, toM = 1000.0)
        s.activity(Activity.STILL)
        stay(s, at = 1000.0, minutes = 20)
        s.events.forEach { tracker.process(it) }

        val segments = journal.segments(0, Long.MAX_VALUE)

        assertEquals(listOf(SegmentKind.STAY, SegmentKind.MOVE, SegmentKind.STAY), segments.map { it.kind })
        val (home, walk, office) = segments
        assertFalse(home.ongoing)
        assertFalse(walk.ongoing)
        assertTrue(office.ongoing)
        assertTrue(walk.distanceM in 950.0..1050.0, "walked 1 km, got ${walk.distanceM}")
        assertEquals(Activity.WALKING, walk.activity)
        assertEquals(home.endMs, walk.startMs)
        assertTrue(walk.endMs <= office.startMs + 3.minutes.inWholeMilliseconds)
    }

    @Test
    fun stoppingTrackingEndsTheOngoingStay() {
        val s = Scenario()
        val tracker = Tracker(database)
        s.activity(Activity.STILL)
        stay(s, at = 0.0, minutes = 20)
        s.events.forEach { tracker.process(it) }

        tracker.finish()

        val stay = journal.segments(0, Long.MAX_VALUE).single()
        assertEquals(SegmentKind.STAY, stay.kind)
        assertFalse(stay.ongoing)
    }

    @Test
    fun aLongSilenceStartsANewMove() {
        val s = Scenario()
        val tracker = Tracker(database)
        s.activity(Activity.WALKING)
        walkEast(s, fromM = 0.0, toM = 500.0)
        s.advance(2.hours)
        walkEast(s, fromM = 5000.0, toM = 5500.0)
        s.events.forEach { tracker.process(it) }
        tracker.finish()

        val moves = journal.segments(0, Long.MAX_VALUE)

        assertEquals(2, moves.size)
        moves.forEach { assertTrue(it.distanceM < 600, "no jump across the gap, got ${it.distanceM}") }
    }

    @Test
    fun segmentsOverlappingTheDayAreFoundAndOldOnesPruned() {
        val s = Scenario()
        val tracker = Tracker(database)
        s.activity(Activity.STILL)
        stay(s, at = 0.0, minutes = 20)
        s.events.forEach { tracker.process(it) }
        tracker.finish()
        val stay = journal.segments(0, Long.MAX_VALUE).single()

        assertEquals(1, journal.segments(stay.startMs + 1, stay.startMs + 2).size)
        assertEquals(0, journal.segments(stay.endMs + 1, stay.endMs + 2).size)

        journal.prune(beforeMs = stay.endMs + 1.days.inWholeMilliseconds)
        assertEquals(0, journal.segments(0, Long.MAX_VALUE).size)
    }

    private fun stay(s: Scenario, at: Double, minutes: Int) {
        repeat(minutes) {
            s.advance(1.minutes)
            s.fix(eastM = at, northM = 0.0)
        }
    }

    private fun walkEast(s: Scenario, fromM: Double, toM: Double) {
        var x = fromM
        while (x < toM) {
            x += 7.0
            s.advance(5.seconds)
            s.fix(eastM = x, northM = 0.0)
        }
    }
}
