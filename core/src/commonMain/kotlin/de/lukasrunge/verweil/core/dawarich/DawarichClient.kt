package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.upload.PointItem
import de.lukasrunge.verweil.core.upload.VisitItem
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class DawarichException(message: String) : Exception(message)

/**
 * Talks to existing Dawarich APIs, so no server changes are needed:
 * track points through the Overland batch endpoint, stays through the visits API.
 */
class DawarichClient(
    baseUrl: String,
    private val apiKey: String,
    private val deviceId: String,
    engine: HttpClient,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    private val http = engine.config {
        install(ContentNegotiation) {
            json(Json { encodeDefaults = true; explicitNulls = false })
        }
    }

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
        if (!status.isSuccess()) throw DawarichException("Dawarich answered ${status.value}: ${bodyAsText().take(500)}")
    }
}

private fun Long.toIsoString(): String = Instant.fromEpochMilliseconds(this).toString()
