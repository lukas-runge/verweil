package de.lukasrunge.verweil.core.upload

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.db.Counts
import de.lukasrunge.verweil.core.db.VerweilDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

data class OutboxCounts(
    val pending: Long,
    /** Items the server refused; they stay until [Outbox.retryRejected]. */
    val rejected: Long,
    val lastError: String?,
)

/** Persistent upload queue between the engine and Dawarich. */
class Outbox(database: VerweilDatabase) {
    private val queries = database.outboxQueries

    fun add(points: List<PointItem>) = queries.transaction {
        points.forEach { queries.insertPoint(it.timeMs, it.lat, it.lon, it.accuracy, it.speed, it.altitude, it.motion, it.motionConfidence) }
    }

    fun counts(): OutboxCounts = queries.counts().executeAsOne().toCounts()

    fun countsFlow(context: CoroutineContext): Flow<OutboxCounts> =
        queries.counts().asFlow().mapToOne(context).map { it.toCounts() }

    /** Drops everything queued and set aside, e.g. when signing out of an account for good. */
    fun clear() = queries.deleteAll()

    /** Queues rejected items again, e.g. after a server update fixed the cause. */
    fun retryRejected() = queries.retryRejected()

    /**
     * Sends everything queued, oldest first.
     *
     * Data the server refuses (a 4xx other than auth or rate limiting) is set aside with its error,
     * so it cannot block the queue. Any other failure stops the flush and leaves the rest queued,
     * so the caller can simply retry later.
     *
     * Returns how many points Dawarich accepted.
     */
    suspend fun flush(client: DawarichClient, batchSize: Long = 500): Int {
        var sent = 0
        while (true) {
            val batch = queries.oldestPoints(batchSize).executeAsList()
            if (batch.isEmpty()) break
            val ids = batch.map { it.id }
            try {
                client.sendPoints(
                    batch.map { PointItem(it.time_ms, it.lat, it.lon, it.accuracy, it.speed, it.altitude, it.motion, it.motion_confidence) },
                )
                queries.deletePoints(ids)
                sent += ids.size
            } catch (e: DawarichException) {
                if (!e.rejectsData) throw e
                queries.rejectPoints(e.message, ids)
            }
        }
        return sent
    }
}

private fun Counts.toCounts() = OutboxCounts(pending, rejected, last_error)
