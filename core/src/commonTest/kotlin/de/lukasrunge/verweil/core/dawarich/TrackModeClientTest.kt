package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.timeline.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackModeClientTest {

    private val requests = mutableListOf<String>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK) = DawarichClient(
        baseUrl = "https://dawarich.example.org",
        apiKey = "secret",
        deviceId = "pixel",
        engine = HttpClient(
            MockEngine { request ->
                val body = if (request.method == HttpMethod.Patch) " " + request.body.toByteArray().decodeToString() else ""
                requests += "${request.method.value} ${request.url.encodedPath}$body"
                val answer = if (request.method == HttpMethod.Patch) AFTER else BEFORE
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun theModesOnOfferAreThoseEnabledInDawarich() = runTest {
        val modes = client().travelModes(trackId = 38)

        assertEquals(listOf(TravelMode.WALKING, TravelMode.CYCLING, TravelMode.DRIVING), modes)
        assertEquals(listOf("GET /api/v1/tracks/38/segments"), requests)
    }

    @Test
    fun aNewModeGoesToEveryMovingSegmentNotAlreadyInIt() = runTest {
        val mode = client().setTravelMode(trackId = 38, mode = TravelMode.DRIVING)

        assertEquals(TravelMode.DRIVING, mode)
        assertEquals(
            listOf(
                "GET /api/v1/tracks/38/segments",
                """PATCH /api/v1/tracks/38/segments/51 {"transportation_mode":"driving"}""",
            ),
            requests,
            "the stationary segment and the one already driving stay as they are",
        )
    }

    @Test
    fun segmentsWithoutTimesCannotSplitATrack() = runTest {
        assertEquals(null, client().trackSegments(trackId = 38), "Dawarich anchored these by point index only")
    }

    @Test
    fun segmentsCarryTheirTimesDistanceAndMode() = runTest {
        val body = """
            {"track_id": 219, "dominant_mode": "cycling", "enabled_modes": ["cycling"], "segments": [
              {"id": 1, "transportation_mode": "cycling", "start_at": "2026-10-01T13:27:00+02:00", "end_at": "2026-10-01T13:31:00+02:00", "distance": 1100},
              {"id": 2, "transportation_mode": "stationary", "start_at": "2026-10-01T13:31:00+02:00", "end_at": "2026-10-01T13:48:00+02:00", "distance": null}
            ]}
        """.trimIndent()
        val client = DawarichClient("https://d.example.org", "secret", "pixel", HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }))

        val (ride, stop) = client.trackSegments(trackId = 219)!!

        assertEquals(TravelMode.CYCLING, ride.mode)
        assertEquals(1100.0, ride.distanceM)
        assertEquals(kotlin.time.Instant.parse("2026-10-01T13:31:00+02:00").toEpochMilliseconds(), ride.endMs)
        assertEquals(TravelMode.UNKNOWN, stop.mode)
        assertEquals(0.0, stop.distanceM)
    }

    @Test
    fun aServerWithoutTheSegmentsApiAnswers404() = runTest {
        val e = assertFailsWith<DawarichException> { client(HttpStatusCode.NotFound).travelModes(trackId = 38) }
        assertEquals(404, e.status)
    }

    private companion object {
        val BEFORE = """
            {"track_id": 38, "dominant_mode": "cycling", "enabled_modes": ["walking", "cycling", "driving", "teleporting"],
             "segments": [
               {"id": 50, "track_id": 38, "transportation_mode": "stationary", "source": "inferred"},
               {"id": 51, "track_id": 38, "transportation_mode": "cycling", "distance": 5000, "source": "inferred"},
               {"id": 52, "track_id": 38, "transportation_mode": "driving", "distance": 900, "source": "inferred"}
             ]}
        """.trimIndent()
        val AFTER = """
            {"track_id": 38, "dominant_mode": "driving", "enabled_modes": ["walking", "cycling", "driving"],
             "segments": [], "segment": {"id": 51, "transportation_mode": "driving", "source": "user"}}
        """.trimIndent()
    }
}
