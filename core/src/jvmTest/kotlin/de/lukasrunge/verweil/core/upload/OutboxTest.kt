package de.lukasrunge.verweil.core.upload

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.db.VerweilDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OutboxTest {

    private val outbox = Outbox(
        VerweilDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VerweilDatabase.Schema.create(it) }),
    )

    private fun client(vararg statuses: HttpStatusCode): Pair<DawarichClient, MutableList<String>> {
        val paths = mutableListOf<String>()
        var call = 0
        val client = DawarichClient(
            baseUrl = "https://dawarich.example.org",
            apiKey = "secret",
            deviceId = "pixel",
            engine = HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    respond("{}", statuses.getOrElse(call++) { HttpStatusCode.Created })
                },
            ),
        )
        return client to paths
    }

    @Test
    fun flushSendsPointsInBatchesBeforeVisits() = runTest {
        outbox.add(List(5) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        outbox.add(listOf(VisitItem(lat = 52.0, lon = 13.0, startedMs = 0, endedMs = 4)))
        val (client, paths) = client()

        outbox.flush(client, batchSize = 2)

        assertEquals(
            listOf(
                "/api/v1/overland/batches",
                "/api/v1/overland/batches",
                "/api/v1/overland/batches",
                "/api/v1/visits",
            ),
            paths,
        )
        assertEquals(0, outbox.pendingPoints())
    }

    @Test
    fun failedUploadKeepsTheRestQueued() = runTest {
        outbox.add(List(4) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        val (client, _) = client(HttpStatusCode.Created, HttpStatusCode.BadGateway)

        assertFails { outbox.flush(client, batchSize = 2) }

        assertEquals(2, outbox.pendingPoints())
    }
}
