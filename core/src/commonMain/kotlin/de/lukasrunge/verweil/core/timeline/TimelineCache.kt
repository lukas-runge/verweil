package de.lukasrunge.verweil.core.timeline

import de.lukasrunge.verweil.core.db.VerweilDatabase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A day of Dawarich's timeline as last fetched. */
data class CachedDay(val entries: List<TimelineEntry>, val fetchedMs: Long)

/** Keeps the last fetched timeline of every day the user looked at. */
class TimelineCache(database: VerweilDatabase) {
    private val queries = database.timelineCacheQueries

    fun get(dayStartMs: Long): CachedDay? {
        val row = queries.get(dayStartMs).executeAsOneOrNull() ?: return null
        return try {
            CachedDay(json.decodeFromString(serializer, row.entries), row.fetched_ms)
        } catch (_: SerializationException) {
            // Written by an incompatible version; the next fetch replaces it.
            null
        }
    }

    fun put(dayStartMs: Long, entries: List<TimelineEntry>, fetchedMs: Long) =
        queries.put(dayStartMs, json.encodeToString(serializer, entries), fetchedMs)

    fun clear() = queries.deleteAll()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(TimelineEntry.serializer())
    }
}
