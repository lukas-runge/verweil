package de.lukasrunge.verweil.core.tracking

import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.EngineState
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.journal.Journal
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.place.PlaceMemory
import de.lukasrunge.verweil.core.place.SqlPlaceStore
import de.lukasrunge.verweil.core.upload.Outbox
import de.lukasrunge.verweil.core.upload.toUploadItems
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The engine with a memory. A step that produces uploads stores them together with the engine's state
 * and the [Journal] entries, in one transaction, so a killed app continues an ongoing stay and never queues anything twice.
 * Other steps save the state only when the mode changes or [saveInterval] has passed: with a fix every
 * second, writing it each time would rewrite hundreds of fixes per second. A kill loses at most that
 * stretch of fixes, never an upload.
 */
class Tracker(
    private val database: VerweilDatabase,
    config: EngineConfig = EngineConfig(),
    private val saveInterval: Duration = 30.seconds,
) {
    private val outbox = Outbox(database)
    private val journal = Journal(database)
    private val stateQueries = database.engineStateQueries
    private val engine = StayEngine(config, PlaceMemory(SqlPlaceStore(database), config), loadState())

    val mode: Mode get() = engine.mode

    /** Where the current stay is pinned, while there is one. */
    val stayAnchor: GeoPoint? get() = engine.stayAnchor

    /** The latest recognised activity. */
    val activity: Activity get() = engine.state.activity

    private var savedMode: Mode? = null
    private var savedAtMs: Long? = null

    fun process(event: SensorEvent): List<EngineOutput> = step(event.timeMs, force = false) { engine.process(event) }

    /** Tracking stops: closes an open stay and starts over next time. */
    fun finish(): List<EngineOutput> =
        step(savedAtMs ?: 0, force = true) { engine.finish() }.also { journal.endOngoing() }

    private fun step(timeMs: Long, force: Boolean, decide: () -> List<EngineOutput>): List<EngineOutput> =
        database.transactionWithResult {
            val outputs = decide()
            val lastSave = savedAtMs
            val due = force || outputs.isNotEmpty() || engine.mode != savedMode ||
                lastSave == null || timeMs - lastSave >= saveInterval.inWholeMilliseconds
            if (due) {
                outbox.add(outputs.flatMap { it.toUploadItems() })
                journal.record(outputs)
                stateQueries.save(json.encodeToString(EngineState.serializer(), engine.state))
                savedMode = engine.mode
                savedAtMs = maxOf(timeMs, lastSave ?: timeMs)
            }
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
