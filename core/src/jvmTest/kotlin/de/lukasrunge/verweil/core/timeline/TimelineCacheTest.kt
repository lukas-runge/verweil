package de.lukasrunge.verweil.core.timeline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineCacheTest {

    private val database = VerweilDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VerweilDatabase.Schema.create(it) })

    @Test
    fun aFetchedDayComesBackAsItWas() {
        val cache = TimelineCache(database)
        val entries = listOf(
            TimelineEntry.Stay(0, 60_000, GeoPoint(52.52, 13.405), "Zuhause", visitId = 7),
            TimelineEntry.Move(60_000, 90_000, 2100.0, TravelMode.TRAIN),
        )

        cache.put(dayStartMs = 0, entries, fetchedMs = 123)

        assertEquals(CachedDay(entries, 123), cache.get(0))
        assertNull(cache.get(86_400_000))
    }
}
