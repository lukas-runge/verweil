package de.lukasrunge.verweil.core.timeline

import de.lukasrunge.verweil.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class StraightenTimelineTest {

    private val here = GeoPoint(52.52, 13.405)

    @Test
    fun aRideRunningIntoTheStaysAroundItEndsWhereTheyBegin() {
        // Dawarich's afternoon of 1 Oct: the ride starts before home ends and ends after the university begins.
        val entries = listOf(stay(0, 717, "Zuhause"), move(713, 734, 4600.0), stay(732, 807, "Büro"))

        val straight = straightenTimeline(entries)

        val ride = straight[1] as TimelineEntry.Move
        assertEquals(minutes(717), ride.startMs)
        assertEquals(minutes(732), ride.endMs)
        assertEquals(4600.0, ride.distanceM, "trimmed at the stays, where nobody moved")
    }

    @Test
    fun aRideAroundAStayBecomesTwoRidesWithTheirSegmentsDistances() {
        val entries = listOf(stay(732, 807, "Büro"), move(807, 839, 5200.0, trackId = 219), stay(811, 828, "Café"), stay(834, 1469, "Kneipe"))
        val segments = mapOf(
            219L to listOf(
                TrackSegment(minutes(807), minutes(811), 1100.0, TravelMode.CYCLING),
                TrackSegment(minutes(811), minutes(828), 0.0, TravelMode.UNKNOWN),
                TrackSegment(minutes(828), minutes(839), 4100.0, TravelMode.CYCLING),
            ),
        )

        val straight = straightenTimeline(entries, segments)

        assertEquals(listOf("Büro", "ride", "Café", "ride", "Kneipe"), straight.map { (it as? TimelineEntry.Stay)?.name ?: "ride" })
        val (first, second) = straight.filterIsInstance<TimelineEntry.Move>()
        assertEquals(minutes(807) to minutes(811), first.startMs to first.endMs)
        assertEquals(1100.0, first.distanceM, 0.1)
        assertEquals(minutes(828) to minutes(834), second.startMs to second.endMs)
        assertEquals(4100.0 * 6 / 11, second.distanceM, 0.1, "the share of the last segment before the pub")
        assertEquals(219L, second.trackId, "both legs still correct the same track")
    }

    @Test
    fun withoutSegmentsTheDistanceIsSplitByTime() {
        val entries = listOf(move(0, 30, 3000.0), stay(10, 20, "Bäcker"))

        val (before, after) = straightenTimeline(entries).filterIsInstance<TimelineEntry.Move>()

        assertEquals(1500.0, before.distanceM, 0.1)
        assertEquals(1500.0, after.distanceM, 0.1)
    }

    @Test
    fun aLegTakesTheModeItsSegmentsMostlyHad() {
        val entries = listOf(move(0, 30, 3000.0, mode = TravelMode.CYCLING, trackId = 1), stay(10, 20, "Bahnhof"))
        val segments = mapOf(
            1L to listOf(
                TrackSegment(minutes(0), minutes(10), 800.0, TravelMode.WALKING),
                TrackSegment(minutes(20), minutes(30), 2200.0, TravelMode.CYCLING),
            ),
        )

        val (before, after) = straightenTimeline(entries, segments).filterIsInstance<TimelineEntry.Move>()

        assertEquals(TravelMode.WALKING, before.mode)
        assertEquals(TravelMode.CYCLING, after.mode)
    }

    @Test
    fun aMoveInsideAStayIsLeftOut() {
        val entries = listOf(stay(0, 60, "Zuhause"), move(20, 25, 80.0))

        assertEquals(1, straightenTimeline(entries).size)
    }

    @Test
    fun onlyRidesAroundAStayNeedTheirSegments() {
        val entries = listOf(stay(0, 717, "Zuhause"), move(713, 734, 4600.0, trackId = 202), move(807, 839, 5200.0, trackId = 219), stay(811, 828, "Café"))

        assertEquals(listOf(219L), entries.tracksToSplit())
    }

    private fun minutes(m: Int) = m * 60_000L

    private fun stay(from: Int, to: Int, name: String) = TimelineEntry.Stay(minutes(from), minutes(to), here, name)

    private fun move(from: Int, to: Int, meters: Double, mode: TravelMode = TravelMode.CYCLING, trackId: Long? = null) =
        TimelineEntry.Move(minutes(from), minutes(to), meters, mode, trackId = trackId)
}
