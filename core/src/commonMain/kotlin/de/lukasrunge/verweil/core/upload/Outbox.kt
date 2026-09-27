package de.lukasrunge.verweil.core.upload

import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.db.VerweilDatabase

/** Persistent upload queue between the engine and Dawarich. */
class Outbox(database: VerweilDatabase) {
    private val queries = database.outboxQueries

    fun add(items: List<UploadItem>) = queries.transaction {
        items.forEach { item ->
            when (item) {
                is PointItem -> queries.insertPoint(
                    item.timeMs, item.lat, item.lon, item.accuracy, item.speed, item.altitude, item.motion,
                )

                is VisitItem -> queries.insertVisit(item.lat, item.lon, item.startedMs, item.endedMs)
            }
        }
    }

    fun pendingPoints(): Long = queries.countPoints().executeAsOne()

    /**
     * Sends everything queued, oldest first. Stops at the first failure and leaves the rest queued,
     * so the caller can simply retry later. Points go before visits, so a visit never precedes its data.
     */
    suspend fun flush(client: DawarichClient, batchSize: Long = 500) {
        while (true) {
            val batch = queries.oldestPoints(batchSize).executeAsList()
            if (batch.isEmpty()) break
            client.sendPoints(
                batch.map { PointItem(it.time_ms, it.lat, it.lon, it.accuracy, it.speed, it.altitude, it.motion) },
            )
            queries.deletePoints(batch.map { it.id })
        }
        while (true) {
            val visit = queries.oldestVisit().executeAsOneOrNull() ?: break
            client.createVisit(VisitItem(visit.lat, visit.lon, visit.started_ms, visit.ended_ms))
            queries.deleteVisit(visit.id)
        }
    }
}
