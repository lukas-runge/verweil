package de.lukasrunge.verweil.core.timeline

import de.lukasrunge.verweil.core.journal.Segment
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeTimelineTest {

    private val here = GeoPoint(52.52, 13.405)

    @Test
    fun withoutDawarichDataThePhoneShowsEverything() {
        val merged = mergeTimeline(emptyList(), listOf(stay(0, 60), move(60, 80), stay(80, 90, ongoing = true)))

        assertEquals(3, merged.size)
        assertTrue(merged.all { it.source == Source.PHONE })
        assertTrue(merged.last().ongoing)
    }

    @Test
    fun onlyWhatComesAfterDawarichIsAdded() {
        val dawarich = listOf(serverStay(0, 60, "Zuhause"), serverMove(60, 80))
        val phone = listOf(stay(0, 60), move(60, 80), stay(80, 90, ongoing = true))

        val merged = mergeTimeline(dawarich, phone)

        assertEquals(3, merged.size)
        assertEquals(listOf(Source.DAWARICH, Source.DAWARICH, Source.PHONE), merged.map { it.source })
        assertEquals(minutes(80), merged.last().startMs)
    }

    @Test
    fun anOngoingStayDawarichAlreadyShowsKeepsDawarichsName() {
        val dawarich = listOf(serverMove(0, 20), serverStay(20, 50, "Büro"))

        val merged = mergeTimeline(dawarich, listOf(stay(20, 60, ongoing = true)))

        val office = merged.last() as TimelineEntry.Stay
        assertEquals(2, merged.size)
        assertEquals("Büro", office.name)
        assertTrue(office.ongoing)
        assertEquals(minutes(60), office.endMs)
    }

    @Test
    fun aMoveDawarichHasNoTrackForYetFollowsItsLastVisit() {
        val dawarich = listOf(serverStay(0, 60, "Zuhause"))

        val merged = mergeTimeline(dawarich, listOf(stay(0, 60), move(60, 75, ongoing = true)))

        val walk = merged.last() as TimelineEntry.Move
        assertEquals(Source.PHONE, walk.source)
        assertEquals(TravelMode.WALKING, walk.mode)
    }

    @Test
    fun aStayOfThePhoneTakesDawarichsNameForTheSamePlace() {
        val dawarich = listOf(serverStay(0, 60, "13407 Klamannstraße 16 (House)"), serverMove(60, 120))
        val nextDoor = GeoPoint(here.lat + 0.0003, here.lon)

        val merged = mergeTimeline(dawarich, listOf(stay(120, 130, ongoing = true, at = nextDoor, name = "Klamannstraße 16, Berlin")))

        assertEquals("13407 Klamannstraße 16 (House)", (merged.last() as TimelineEntry.Stay).name)
        assertEquals(1, merged.placeCount())
    }

    @Test
    fun aStayDawarichDoesNotHaveFillsItsGap() {
        // Dawarich's own visit detection replaced the stay with nothing; its tracks go on around it.
        val dawarich = listOf(serverMove(0, 60), serverMove(160, 170))
        val phone = listOf(stay(60, 155), move(155, 172, ongoing = true))

        val merged = mergeTimeline(dawarich, phone)

        val lost = merged.filterIsInstance<TimelineEntry.Stay>().single()
        assertEquals(Source.PHONE_MISSING, lost.source)
        assertEquals(minutes(60), lost.startMs)
        assertEquals(listOf(minutes(0), minutes(60), minutes(160)), merged.map { it.startMs }, "in time order")
    }

    @Test
    fun anOngoingWalkDawarichAlreadyTracksIsOneRow() {
        val dawarich = listOf(serverStay(0, 60, "Zuhause"), serverMove(61, 63, meters = 300.0))
        val phone = listOf(stay(0, 60), move(60, 65, ongoing = true, meters = 490.0))

        val merged = mergeTimeline(dawarich, phone)

        val walk = merged.last() as TimelineEntry.Move
        assertEquals(2, merged.size, "no second walk next to Dawarich's")
        assertTrue(walk.ongoing)
        assertEquals(minutes(61), walk.startMs)
        assertEquals(minutes(65), walk.endMs)
        // Dawarich's 300 m, and the phone's distance for the two minutes Dawarich has not seen yet.
        assertEquals(300.0 + 490.0 * 2 / 5, walk.distanceM, 0.1)
    }

    @Test
    fun aStayOnlyPartlyInDawarichIsShownAndOlderEntriesKeepTheirEnd() {
        // Dawarich's morning visit runs into the afternoon, across a walk; the phone saw a later stay of its own.
        val dawarich = listOf(serverStay(0, 100, "Zuhause"), serverMove(50, 70), serverMove(150, 160))
        val phone = listOf(stay(70, 145), move(145, 160))

        val merged = mergeTimeline(dawarich, phone)

        assertEquals(minutes(100), merged.first().endMs, "Dawarich's visit is not stretched")
        val afternoon = merged.filterIsInstance<TimelineEntry.Stay>().single { it.source == Source.PHONE_MISSING }
        assertEquals(minutes(70), afternoon.startMs)
        assertEquals("Zuhause", afternoon.name)
    }

    @Test
    fun aStayThatJustBeganIsShown() {
        // Until its first heartbeat, an ongoing stay ends where it starts.
        val dawarich = listOf(serverStay(0, 100, "Zuhause"), serverMove(150, 166))

        val merged = mergeTimeline(dawarich, listOf(stay(166, 166, ongoing = true)))

        val now = merged.last() as TimelineEntry.Stay
        assertEquals(Source.PHONE, now.source)
        assertTrue(now.ongoing)
    }

    private fun minutes(m: Int) = m * 60_000L

    private fun stay(from: Int, to: Int, ongoing: Boolean = false, at: GeoPoint = here, name: String? = null) =
        Segment(0, SegmentKind.STAY, minutes(from), minutes(to), ongoing, at, 0.0, emptyMap(), name)

    private fun move(from: Int, to: Int, ongoing: Boolean = false, meters: Double = 900.0) =
        Segment(0, SegmentKind.MOVE, minutes(from), minutes(to), ongoing, here, meters, mapOf(Activity.WALKING to meters), null)

    private fun serverStay(from: Int, to: Int, name: String) = TimelineEntry.Stay(minutes(from), minutes(to), here, name)

    private fun serverMove(from: Int, to: Int, meters: Double = 1200.0) =
        TimelineEntry.Move(minutes(from), minutes(to), meters, TravelMode.WALKING)
}
