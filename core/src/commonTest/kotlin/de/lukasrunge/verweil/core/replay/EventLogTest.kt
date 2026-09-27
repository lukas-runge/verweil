package de.lukasrunge.verweil.core.replay

import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.model.WifiScan
import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogTest {
    @Test
    fun eventsSurviveARoundTrip() {
        val events = listOf(
            Fix(timeMs = 1, lat = 52.52, lon = 13.405, accuracy = 12.5, speed = 1.3),
            ActivityChange(timeMs = 2, activity = Activity.STILL),
            WifiScan(timeMs = 3, bssids = setOf("a1", "b2")),
            Tick(timeMs = 4),
        )

        val lines = events.map(EventLog::encode)

        assertEquals("""{"type":"activity","timeMs":2,"activity":"STILL"}""", lines[1])
        assertEquals(events, EventLog.decode(lines.asSequence()).toList())
    }
}
