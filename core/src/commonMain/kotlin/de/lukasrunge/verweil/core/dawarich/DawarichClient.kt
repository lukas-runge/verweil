package de.lukasrunge.verweil.core.dawarich

import de.lukasrunge.verweil.core.upload.PointItem
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.timeline.TimelineEntry
import de.lukasrunge.verweil.core.timeline.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
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
 * Talks to existing Dawarich APIs: points through the Overland batch endpoint, the day back through the timeline API,
 * and corrections of a track's mode through the track segments API, where the server has it. Visits are Dawarich's:
 * it detects them from the points.
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

    /**
     * Dawarich's timeline for [fromMs] until [toMs] (at most 31 days): its visits, named by Dawarich, and its tracks,
     * with distance and mode of travel. Declined visits and tracks that did not move are left out.
     * Needs Dawarich 1.3 or later; older servers answer 404.
     */
    suspend fun timeline(fromMs: Long, toMs: Long): List<TimelineEntry> {
        val response = http.get("$baseUrl/api/v1/timeline") {
            bearerAuth(apiKey)
            parameter("start_at", fromMs.toIsoString())
            parameter("end_at", toMs.toIsoString())
            parameter("distance_unit", "km")
        }
        response.requireSuccess()
        val days = response.body<TimelineResponse>().days
        // A journey across midnight shows up on both days; the first one carries the whole track.
        return days.flatMap { it.entries }
            .distinctBy { it.visitId?.let { id -> "visit $id" } ?: it.trackId?.let { id -> "track $id" } ?: it.startedAt }
            .mapNotNull { it.toEntry() }
            .sortedBy { it.startMs }
    }

    /**
     * The modes of travel the user may pick for Dawarich's track [trackId]: those enabled in Dawarich's settings.
     * Needs the track segments API (`GET /api/v1/tracks/:id/segments`); servers without it answer 404.
     */
    suspend fun travelModes(trackId: Long): List<TravelMode> =
        segments(trackId).enabledModes.map(::travelMode).filter { it != TravelMode.UNKNOWN }

    /**
     * Corrects the track's mode of travel: every moving segment gets [mode], as if the user picked it in Dawarich.
     * Returns the track's mode as Dawarich now sees it.
     */
    suspend fun setTravelMode(trackId: Long, mode: TravelMode): TravelMode {
        val name = mode.dawarichName() ?: throw IllegalArgumentException("Dawarich has no mode for $mode")
        val track = segments(trackId)
        var dominant = track.dominantMode
        track.segments.filter { it.transportationMode != STATIONARY && it.transportationMode != name }.forEach { segment ->
            val response = http.patch("$baseUrl/api/v1/tracks/$trackId/segments/${segment.id}") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(SegmentModeRequest(name))
            }
            response.requireSuccess()
            dominant = response.body<TrackSegmentsDto>().dominantMode
        }
        return travelMode(dominant)
    }

    private suspend fun segments(trackId: Long): TrackSegmentsDto {
        val response = http.get("$baseUrl/api/v1/tracks/$trackId/segments") { bearerAuth(apiKey) }
        response.requireSuccess()
        return response.body()
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

private fun String.toEpochMs(): Long = Instant.parse(this).toEpochMilliseconds()

private fun TimelineEntryDto.toEntry(): TimelineEntry? = when (type) {
    "visit" -> if (status == "declined") null else TimelineEntry.Stay(
        startMs = startedAt.toEpochMs(),
        endMs = endedAt.toEpochMs(),
        point = place?.let { p -> if (p.lat != null && p.lng != null) GeoPoint(p.lat, p.lng) else null }
            ?: area?.let { a -> if (a.lat != null && a.lng != null) GeoPoint(a.lat, a.lng) else null },
        // Dawarich's own order (TimelineHelper#visit_entry_display_name), so a visit renamed in Dawarich shows renamed.
        // Visits created with Dawarich's placeholder name "Suggested place" keep it at a place Dawarich already knows.
        name = listOf(name, place?.name, area?.name).firstOrNull { !it.isNullOrBlank() && it != SUGGESTED_PLACE },
        visitId = visitId,
    )
    // Stationary tracks are stays Dawarich did not make a visit of; the visits already cover them.
    "journey" -> if (dominantMode == "stationary") null else TimelineEntry.Move(
        startMs = startedAt.toEpochMs(),
        endMs = endedAt.toEpochMs(),
        distanceM = (distance ?: 0.0) * if (distanceUnit == "mi") METERS_PER_MILE else 1000.0,
        mode = travelMode(dominantMode),
        trackId = trackId,
    )
    else -> null
}

private fun travelMode(mode: String?): TravelMode = when (mode) {
    "walking" -> TravelMode.WALKING
    "running" -> TravelMode.RUNNING
    "cycling" -> TravelMode.CYCLING
    "driving" -> TravelMode.DRIVING
    "motorcycle" -> TravelMode.MOTORCYCLE
    "bus" -> TravelMode.BUS
    "train" -> TravelMode.TRAIN
    "flying" -> TravelMode.FLYING
    "boat" -> TravelMode.BOAT
    else -> TravelMode.UNKNOWN
}

private fun TravelMode.dawarichName(): String? = when (this) {
    TravelMode.WALKING -> "walking"
    TravelMode.RUNNING -> "running"
    TravelMode.CYCLING -> "cycling"
    TravelMode.DRIVING -> "driving"
    TravelMode.MOTORCYCLE -> "motorcycle"
    TravelMode.BUS -> "bus"
    TravelMode.TRAIN -> "train"
    TravelMode.FLYING -> "flying"
    TravelMode.BOAT -> "boat"
    TravelMode.VEHICLE, TravelMode.UNKNOWN -> null
}

private const val STATIONARY = "stationary"

private const val METERS_PER_MILE = 1609.344
