package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.upload.PointItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DawarichClientTest {

    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private val authHeaders = mutableListOf<String?>()

    private fun client(status: HttpStatusCode = HttpStatusCode.Created) = DawarichClient(
        baseUrl = "https://dawarich.example.org/",
        apiKey = "secret",
        deviceId = "pixel",
        engine = HttpClient(
            MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                requests += "${request.method.value} ${request.url.encodedPath}" to Json.parseToJsonElement(body).jsonObject
                authHeaders += request.headers[HttpHeaders.Authorization]
                respond("{}", status)
            },
        ),
    )

    @Test
    fun pointsAreSentAsOverlandBatch() = runTest {
        client().sendPoints(
            listOf(PointItem(timeMs = 1_790_000_000_000, lat = 52.52, lon = 13.405, accuracy = 8.0, motion = "walking")),
        )

        val (path, body) = requests.single()
        assertEquals("POST /api/v1/overland/batches", path)
        assertEquals("Bearer secret", authHeaders.single())
        val feature = body["locations"]!!.jsonArray.single().jsonObject
        assertEquals("Feature", feature["type"]!!.jsonPrimitive.content)
        val coordinates = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(listOf(13.405, 52.52), coordinates.map { it.jsonPrimitive.double })
        val properties = feature["properties"]!!.jsonObject
        assertEquals("2026-09-21T14:13:20Z", properties["timestamp"]!!.jsonPrimitive.content)
        assertEquals(8.0, properties["horizontal_accuracy"]!!.jsonPrimitive.double)
        assertEquals("walking", properties["motion"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("pixel", properties["device_id"]!!.jsonPrimitive.content)
        assertFalse("speed" in properties, "unknown values are omitted, not sent as null")
        assertFalse("motion_confidence" in properties, "a guess goes without a confidence")
    }

    @Test
    fun aCertainMotionSaysSo() = runTest {
        client().sendPoints(listOf(PointItem(timeMs = 0, lat = 52.52, lon = 13.405, motion = "driving", motionConfidence = 1.0)))

        val properties = requests.single().second["locations"]!!.jsonArray.single().jsonObject["properties"]!!.jsonObject
        assertEquals("driving", properties["motion"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1.0, properties["motion_confidence"]!!.jsonPrimitive.double)
    }

    @Test
    fun serverErrorsFail() = runTest {
        assertFailsWith<DawarichException> {
            client(HttpStatusCode.Unauthorized).sendPoints(listOf(PointItem(timeMs = 0, lat = 0.0, lon = 0.0)))
        }
    }
}
