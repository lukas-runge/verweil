package de.lukasrunge.verweil

import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.platformHttpClient
import de.lukasrunge.verweil.core.timeline.CachedDay
import de.lukasrunge.verweil.core.timeline.TimelineEntry
import de.lukasrunge.verweil.core.timeline.TrackSegment
import de.lukasrunge.verweil.core.timeline.straightenTimeline
import de.lukasrunge.verweil.core.timeline.tracksToSplit
import de.lukasrunge.verweil.core.timeline.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Why Dawarich's timeline could not be loaded; decides what the app tells the user. */
enum class TimelineProblem {
    /** No connection, or the server did not answer. */
    UNREACHABLE,

    /** Dawarich before 1.3 has no timeline API. */
    TOO_OLD,

    /** The API key was refused. */
    REFUSED,
    OTHER,
}

/** A day of Dawarich's timeline: the entries, as fetched now or from the cache, and whether the fetch worked. */
data class DawarichDay(
    val entries: List<TimelineEntry>?,
    val fetchedMs: Long?,
    val loading: Boolean,
    val problem: TimelineProblem?,
)

/** Loads a day of Dawarich's timeline and keeps it for the next time and for offline use. */
class DawarichTimeline(private val app: VerweilApp) {

    suspend fun cached(dayStartMs: Long): CachedDay? = withContext(Dispatchers.IO) { app.timelineCache.get(dayStartMs) }

    /** Fetches the day; on failure keeps what was cached before. */
    suspend fun fetch(dayStartMs: Long, dayEndMs: Long): DawarichDay {
        val cached = cached(dayStartMs)
        val settings = app.settings.current()
        val http = platformHttpClient()
        return try {
            val client = DawarichClient(settings.serverUrl, settings.apiKey, settings.deviceId, http, settings.customHeaders)
            val entries = client.timeline(dayStartMs, dayEndMs).let { day ->
                // Dawarich's tracks run through the stays; its segments tell where each leg goes.
                straightenTimeline(day, day.tracksToSplit().associateWith { id -> client.segmentsOrNull(id) }.filterNotNullValues())
            }
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { app.timelineCache.put(dayStartMs, entries, now) }
            DawarichDay(entries, now, loading = false, problem = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DawarichDay(cached?.entries, cached?.fetchedMs, loading = false, problem = e.toProblem())
        } finally {
            http.close()
        }
    }

    /** The modes the user may pick for Dawarich's track; fails on servers without the track segments API. */
    suspend fun travelModes(trackId: Long): List<TravelMode> = withClient { it.travelModes(trackId) }

    /** Corrects the track's mode in Dawarich; returns its mode as Dawarich now sees it. */
    suspend fun setTravelMode(trackId: Long, mode: TravelMode): TravelMode = withClient { it.setTravelMode(trackId, mode) }

    private suspend fun <T> withClient(block: suspend (DawarichClient) -> T): T {
        val settings = app.settings.current()
        val http = platformHttpClient()
        return try {
            block(DawarichClient(settings.serverUrl, settings.apiKey, settings.deviceId, http, settings.customHeaders))
        } finally {
            http.close()
        }
    }
}

/** Null on servers without the track segments API, or when a track cannot be read: its legs are split by time. */
private suspend fun DawarichClient.segmentsOrNull(trackId: Long): List<TrackSegment>? = try {
    trackSegments(trackId)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private fun <K, V : Any> Map<K, V?>.filterNotNullValues(): Map<K, V> = buildMap { this@filterNotNullValues.forEach { (k, v) -> if (v != null) put(k, v) } }

private fun Exception.toProblem(): TimelineProblem = when {
    this is DawarichException && status == 404 -> TimelineProblem.TOO_OLD
    this is DawarichException && isAuthError -> TimelineProblem.REFUSED
    this is IOException -> TimelineProblem.UNREACHABLE
    else -> TimelineProblem.OTHER
}
