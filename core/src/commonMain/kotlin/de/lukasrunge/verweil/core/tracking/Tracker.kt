package de.lukasrunge.verweil.core.tracking

import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.EngineState
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.place.PlaceMemory
import de.lukasrunge.verweil.core.place.SqlPlaceStore
import de.lukasrunge.verweil.core.upload.Outbox
import de.lukasrunge.verweil.core.upload.toUploadItems
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The engine with a memory. Every step stores the engine's state together with the uploads it produced,
 * in one transaction, so a killed app continues an ongoing stay and never queues anything twice.
 */
class Tracker(private val database: VerweilDatabase, config: EngineConfig = EngineConfig()) {
    private val outbox = Outbox(database)
    private val stateQueries = database.engineStateQueries
    private val engine = StayEngine(config, PlaceMemory(SqlPlaceStore(database), config), loadState())

    val mode: Mode get() = engine.mode

    /** Where the current stay is pinned, while there is one. */
    val stayAnchor: GeoPoint? get() = engine.stayAnchor

    fun process(event: SensorEvent): List<EngineOutput> = step { engine.process(event) }

    /** Tracking stops: closes an open stay and starts over next time. */
    fun finish(): List<EngineOutput> = step { engine.finish() }

    private fun step(decide: () -> List<EngineOutput>): List<EngineOutput> = database.transactionWithResult {
        val outputs = decide()
        outbox.add(outputs.flatMap { it.toUploadItems() })
        stateQueries.save(json.encodeToString(EngineState.serializer(), engine.state))
        outputs
    }

    private fun loadState(): EngineState {
        val saved = stateQueries.load().executeAsOneOrNull() ?: return EngineState()
        return try {
            json.decodeFromString(EngineState.serializer(), saved)
        } catch (_: SerializationException) {
            // Written by an incompatible version: losing one stay beats crashing on every start.
            EngineState()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
