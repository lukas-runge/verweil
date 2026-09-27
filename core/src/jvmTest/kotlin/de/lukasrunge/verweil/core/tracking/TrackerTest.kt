package de.lukasrunge.verweil.core.tracking

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.engine.Scenario
import de.lukasrunge.verweil.core.journal.Journal
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.place.SqlPlaceStore
import de.lukasrunge.verweil.core.upload.Outbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TrackerTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VerweilDatabase.Schema.create(it) }
    private val database = VerweilDatabase(driver)

    @Test
    fun aRestartedTrackerContinuesTheStay() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stay(s, minutes = 20)
        // A fresh tracker for every event, as if the app was killed after each one.
        val beforeKill = s.events.flatMap { Tracker(database).process(it) }
        s.events.clear()

        val tracker = Tracker(database)
        assertEquals(Mode.STAYING, tracker.mode)
        assertNotNull(tracker.stayAnchor)

        s.activity(Activity.WALKING)
        repeat(60) { i ->
            s.fix(eastM = i * 7.0, northM = 0.0)
            s.advance(5.seconds)
        }
        val afterRestart = s.events.flatMap { tracker.process(it) }

        assertEquals(1, beforeKill.filterIsInstance<StayStarted>().size)
        assertEquals(1, afterRestart.filterIsInstance<StayEnded>().size)
    }

    @Test
    fun finishingQueuesTheDepartureAndForgetsTheStay() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stay(s, minutes = 20, wifi = true)
        val tracker = Tracker(database)
        s.events.forEach { tracker.process(it) }

        tracker.finish()

        assertEquals(Mode.MOVING, Tracker(database).mode)
        // Arrival point, a heartbeat every 5 minutes of the 15 after arrival and the departure point. No visit:
        // Dawarich detects the stay from these points itself.
        assertEquals(5, Outbox(database).counts().pending)
        assertEquals(1, SqlPlaceStore(database).all().size)
    }

    @Test
    fun aStayPausedLessThanAnHourGoesOnWithoutAnyPointForThePause() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stay(s, minutes = 20)
        val tracker = Tracker(database)
        s.events.forEach { tracker.process(it) }
        val queued = Outbox(database).counts().pending

        assertEquals(emptyList(), tracker.pause(s.nowMs))
        assertEquals(queued, Outbox(database).counts().pending, "pausing sends nothing")
        val paused = Journal(database).latest()!!
        assertFalse(paused.ongoing, "the phone shows the stay ended for now")

        // Tracking starts again 30 minutes later, at the same place.
        s.events.clear()
        s.advance(30.minutes)
        stay(s, minutes = 10)
        val resumed = Tracker(database)
        val outputs = s.events.flatMap { resumed.process(it) }

        assertEquals(Mode.STAYING, resumed.mode)
        assertTrue(outputs.none { it is StayEnded || it is StayStarted }, "one stay, no departure, no arrival")
        val stay = Journal(database).segments(0, Long.MAX_VALUE).single()
        assertTrue(stay.ongoing)
        assertEquals(paused.startMs, stay.startMs)
    }

    @Test
    fun aStayPausedLongerEndsAtItsLastEvidenceWhenTrackingStartsAgain() {
        val s = Scenario()
        s.activity(Activity.STILL)
        stay(s, minutes = 20)
        val tracker = Tracker(database)
        s.events.forEach { tracker.process(it) }
        val lastEvidence = s.nowMs
        tracker.pause(s.nowMs)

        s.events.clear()
        s.advance(2.hours)
        s.fix(eastM = 0.0, northM = 0.0)
        val outputs = s.events.flatMap { Tracker(database).process(it) }

        val ended = outputs.filterIsInstance<StayEnded>().single()
        assertEquals(lastEvidence, ended.untilMs, "the stay ends where the evidence ends, not when tracking restarted")
        val stay = Journal(database).segments(0, Long.MAX_VALUE).single { it.kind == SegmentKind.STAY }
        assertFalse(stay.ongoing, "the stay is not listed twice")
        assertEquals(lastEvidence, stay.endMs)
    }

    @Test
    fun pausingWhileMovingEndsTheTrackAsBefore() {
        val s = Scenario()
        s.activity(Activity.WALKING)
        repeat(60) { i ->
            s.fix(eastM = i * 7.0, northM = 0.0)
            s.advance(5.seconds)
        }
        val tracker = Tracker(database)
        s.events.forEach { tracker.process(it) }

        val outputs = tracker.pause(s.nowMs)

        assertTrue(outputs.isNotEmpty(), "the held-back end of the track goes out")
        assertEquals(Mode.MOVING, Tracker(database).mode)
    }

    @Test
    fun theFirstSchemaMigratesWithoutLosingQueuedData() {
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        old.execute(
            null,
            """
            CREATE TABLE pending_point (
                id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
                accuracy REAL, speed REAL, altitude REAL, motion TEXT
            )
            """.trimIndent(),
            0,
        )
        old.execute(
            null,
            """
            CREATE TABLE pending_visit (
                id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL NOT NULL, lon REAL NOT NULL,
                started_ms INTEGER NOT NULL, ended_ms INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        old.execute(null, "INSERT INTO pending_point (time_ms, lat, lon) VALUES (0, 52.0, 13.0)", 0)

        VerweilDatabase.Schema.migrate(old, oldVersion = 1, newVersion = VerweilDatabase.Schema.version)

        val migrated = VerweilDatabase(old)
        assertEquals(1, Outbox(migrated).counts().pending)
        assertEquals(Mode.MOVING, Tracker(migrated).mode)
    }

    private fun stay(s: Scenario, minutes: Int, wifi: Boolean = false) {
        repeat(minutes) {
            s.advance(1.minutes)
            s.fix(eastM = 0.0, northM = 0.0)
            if (wifi && it % 5 == 0) s.wifi("a", "b", "c")
        }
    }
}
