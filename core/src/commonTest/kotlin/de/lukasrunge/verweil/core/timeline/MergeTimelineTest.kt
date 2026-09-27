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

    private fun minutes(m: Int) = m * 60_000L

    private fun stay(from: Int, to: Int, ongoing: Boolean = false) =
        Segment(0, SegmentKind.STAY, minutes(from), minutes(to), ongoing, here, 0.0, emptyMap(), null)

    private fun move(from: Int, to: Int, ongoing: Boolean = false) =
        Segment(0, SegmentKind.MOVE, minutes(from), minutes(to), ongoing, here, 900.0, mapOf(Activity.WALKING to 900.0), null)

    private fun serverStay(from: Int, to: Int, name: String) = TimelineEntry.Stay(minutes(from), minutes(to), here, name)

    private fun serverMove(from: Int, to: Int) = TimelineEntry.Move(minutes(from), minutes(to), 1200.0, TravelMode.WALKING)
}
