package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.upload.PointItem
import de.lukasrunge.verweil.core.upload.VisitItem
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Instant

/** [status] is the HTTP status when the server answered at all. */
class DawarichException(message: String, val status: Int? = null) : Exception(message) {
    /** The key or the proxy credentials were refused. Retrying the same data cannot help until they change. */
    val isAuthError: Boolean get() = status == 401 || status == 403

    /** The server refused this particular data. Retrying it cannot help; other data may still go through. */
    val rejectsData: Boolean get() = status != null && status in 400..499 && !isAuthError && status != 408 && status != 429
}

/**
 * Talks to existing Dawarich APIs, so no server changes are needed:
 * track points through the Overland batch endpoint, stays through the visits API.
 * [customHeaders] go along with every request, for servers behind an authenticating reverse proxy.
 */
class DawarichClient(
    baseUrl: String,
    private val apiKey: String,
    private val deviceId: String,
    engine: HttpClient,
    customHeaders: Map<String, String> = emptyMap(),
) {
    private val baseUrl = baseUrl.trimEnd('/')

    private val http = engine.dawarichConfig(customHeaders)

    suspend fun sendPoints(points: List<PointItem>) {
        if (points.isEmpty()) return
        val batch = OverlandBatch(points.map { it.toFeature() })
        http.post("$baseUrl/api/v1/overland/batches") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(batch)
        }.requireSuccess()
    }

    suspend fun createVisit(visit: VisitItem) {
        val body = VisitRequest(
            VisitBody(
                latitude = visit.lat,
                longitude = visit.lon,
                startedAt = visit.startedMs.toIsoString(),
                endedAt = visit.endedMs.toIsoString(),
            ),
        )
        http.post("$baseUrl/api/v1/visits") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireSuccess()
    }

    private fun PointItem.toFeature() = OverlandFeature(
        geometry = PointGeometry(coordinates = listOf(lon, lat)),
        properties = OverlandProperties(
            timestamp = timeMs.toIsoString(),
            horizontalAccuracy = accuracy,
            speed = speed,
            altitude = altitude,
            motion = motion?.let { listOf(it) },
            deviceId = deviceId,
        ),
    )

    private suspend fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw DawarichException("Dawarich answered ${status.value}: ${bodyAsText().take(500)}", status.value)
    }
}

private fun Long.toIsoString(): String = Instant.fromEpochMilliseconds(this).toString()
