package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.timeline.PlaceTag
import de.lukasrunge.verweil.core.timeline.TimelineEntry
import de.lukasrunge.verweil.core.timeline.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimelineClientTest {

    private val urls = mutableListOf<Url>()

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) = DawarichClient(
        baseUrl = "https://dawarich.example.org",
        apiKey = "secret",
        deviceId = "pixel",
        engine = HttpClient(
            MockEngine { request ->
                urls += request.url
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun visitsAndJourneysBecomeStaysAndMoves() = runTest {
        val entries = client(DAY).timeline(fromMs = 1_790_460_000_000, toMs = 1_790_546_400_000)

        val url = urls.single()
        assertEquals("/api/v1/timeline", url.encodedPath)
        assertEquals("2026-09-26T22:00:00Z", url.parameters["start_at"])
        assertEquals("km", url.parameters["distance_unit"])

        assertEquals(3, entries.size, "declined visit and stationary track are left out")
        val (home, walk, office) = entries
        home as TimelineEntry.Stay
        assertEquals("Home", home.name, "the visit's name comes first, as in Dawarich")
        assertEquals(GeoPoint(52.52, 13.405), home.point)
        assertEquals(7, home.visitId)
        assertEquals(listOf(PlaceTag("Home", icon = "🏡", color = "#FF5733")), home.tags, "the tags of Dawarich's place")
        walk as TimelineEntry.Move
        assertEquals(TravelMode.WALKING, walk.mode)
        assertEquals(2100.0, walk.distanceM)
        assertEquals(kotlin.time.Instant.parse("2026-09-27T09:22:00+02:00").toEpochMilliseconds(), walk.startMs)
        office as TimelineEntry.Stay
        assertEquals("Büro", office.name, "\"Suggested place\" is not a name; the place's is")
    }

    @Test
    fun aJourneyAcrossMidnightIsListedOnce() = runTest {
        val body = """
            {"days": [
              {"date": "2026-09-26", "entries": [$NIGHT_RIDE]},
              {"date": "2026-09-27", "entries": [$NIGHT_RIDE]}
            ]}
        """.trimIndent()

        assertEquals(1, client(body).timeline(0, 1).size)
    }

    @Test
    fun anOldServerAnswers404() = runTest {
        val error = assertFailsWith<DawarichException> { client("Not Found", HttpStatusCode.NotFound).timeline(0, 1) }
        assertEquals(404, error.status)
    }

    private companion object {
        const val NIGHT_RIDE = """
            {"type": "journey", "track_id": 3, "started_at": "2026-09-26T23:40:00+02:00",
             "ended_at": "2026-09-27T00:20:00+02:00", "distance": 12.4, "distance_unit": "km", "dominant_mode": "train"}
        """

        val DAY = """
            {"days": [{"date": "2026-09-27", "summary": {"total_distance": 2.1}, "entries": [
              {"type": "visit", "visit_id": 7, "name": "Home", "status": "confirmed",
               "started_at": "2026-09-27T07:10:00+02:00", "ended_at": "2026-09-27T09:22:00+02:00", "duration": 132,
               "place": {"name": "Zuhause", "lat": 52.52, "lng": 13.405, "city": "Berlin", "country": "Germany"},
               "area": null, "tags": [{"id": 1, "name": "Home", "icon": "🏡", "color": "#FF5733"}], "suggested_places": []},
              {"type": "journey", "track_id": 1, "started_at": "2026-09-27T09:22:00+02:00",
               "ended_at": "2026-09-27T09:40:00+02:00", "duration": 1080, "distance": 2.1, "distance_unit": "km",
               "dominant_mode": "walking", "avg_speed": 7.0, "speed_unit": "km/h", "continuation_of_date": null},
              {"type": "journey", "track_id": 2, "started_at": "2026-09-27T09:40:00+02:00",
               "ended_at": "2026-09-27T09:45:00+02:00", "distance": 0.3, "distance_unit": "km", "dominant_mode": "stationary"},
              {"type": "visit", "visit_id": 8, "name": "Suggested place", "status": "suggested",
               "started_at": "2026-09-27T09:40:00+02:00", "ended_at": "2026-09-27T17:05:00+02:00",
               "place": {"name": "Büro", "lat": 52.5219, "lng": 13.4132}, "area": null},
              {"type": "visit", "visit_id": 9, "name": "Bakery", "status": "declined",
               "started_at": "2026-09-27T17:10:00+02:00", "ended_at": "2026-09-27T17:20:00+02:00", "place": null}
            ]}]}
        """.trimIndent()
    }
}
