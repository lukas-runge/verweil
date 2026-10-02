package de.lukasrunge.verweil.core.upload

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.lukasrunge.verweil.core.dawarich.DawarichClient
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.db.VerweilDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun flushSendsPointsInBatches() = runTest {
        outbox.add(List(5) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        val (client, paths) = client()

        assertEquals(5, outbox.flush(client, batchSize = 2))
        assertEquals(List(3) { "/api/v1/overland/batches" }, paths)
        assertEquals(0, outbox.counts().pending)
    }

    @Test
    fun aCertainMotionSurvivesTheQueue() = runTest {
        outbox.add(listOf(PointItem(timeMs = 0, lat = 52.0, lon = 13.0, motion = "driving", motionConfidence = 1.0)))
        val bodies = mutableListOf<String>()
        val client = DawarichClient(
            "https://dawarich.example.org", "secret", "pixel",
            HttpClient(MockEngine { request -> bodies += (request.body as TextContent).text; respond("{}", HttpStatusCode.Created) }),
        )

        outbox.flush(client, batchSize = 10)

        assertTrue("\"motion_confidence\":1.0" in bodies.single())
    }

    @Test
    fun failedUploadKeepsTheRestQueued() = runTest {
        outbox.add(List(4) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        val (client, _) = client(HttpStatusCode.Created, HttpStatusCode.BadGateway)

        assertFails { outbox.flush(client, batchSize = 2) }

        assertEquals(2, outbox.counts().pending)
    }

    @Test
    fun refusedDataIsSetAsideSoTheRestStillGoes() = runTest {
        outbox.add(List(4) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        val (client, paths) = client(HttpStatusCode.UnprocessableEntity, HttpStatusCode.Created)

        assertEquals(2, outbox.flush(client, batchSize = 2))
        assertEquals(2, paths.size)
        val counts = outbox.counts()
        assertEquals(0, counts.pending)
        assertEquals(2, counts.rejected)
        assertTrue(counts.lastError!!.contains("422"))

        outbox.retryRejected()
        assertEquals(OutboxCounts(pending = 2, rejected = 0, lastError = null), outbox.counts())
    }

    @Test
    fun aRefusedKeyKeepsEverythingQueued() = runTest {
        outbox.add(List(2) { PointItem(timeMs = it.toLong(), lat = 52.0, lon = 13.0) })
        val (client, _) = client(HttpStatusCode.Unauthorized)

        val error = assertFailsWith<DawarichException> { outbox.flush(client) }

        assertTrue(error.isAuthError)
        assertEquals(OutboxCounts(pending = 2, rejected = 0, lastError = null), outbox.counts())
    }

    @Test
    fun rateLimitsAreRetriedNotRefused() = runTest {
        outbox.add(listOf(PointItem(timeMs = 0, lat = 52.0, lon = 13.0)))
        val (client, _) = client(HttpStatusCode.TooManyRequests)

        assertFails { outbox.flush(client) }

        assertEquals(1, outbox.counts().pending)
    }
}
