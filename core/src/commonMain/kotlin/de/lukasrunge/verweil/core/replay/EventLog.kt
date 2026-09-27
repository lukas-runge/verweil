package de.lukasrunge.verweil.core.replay

import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.SensorEvent
import kotlinx.serialization.json.Json

/**
 * Raw sensor events as JSON Lines, one event per line.
 * The recorder writes these on the phone; replays feed them back through the engine.
 */
object EventLog {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(event: SensorEvent): String = json.encodeToString(SensorEvent.serializer(), event)

    fun decode(lines: Sequence<String>): Sequence<SensorEvent> =
        lines.filter { it.isNotBlank() }.map { json.decodeFromString(SensorEvent.serializer(), it) }
}

/** Runs recorded events through a fresh engine, e.g. to compare thresholds on real days. */
fun replay(events: Sequence<SensorEvent>, config: EngineConfig = EngineConfig()): List<EngineOutput> {
    val engine = StayEngine(config)
    return events.sortedBy { it.timeMs }.flatMap { engine.process(it) }.toList()
}
