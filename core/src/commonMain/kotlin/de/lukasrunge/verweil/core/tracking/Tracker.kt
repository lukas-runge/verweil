package de.lukasrunge.verweil.core.tracking

import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.EngineState
import de.lukasrunge.verweil.core.engine.Mode
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.journal.Journal
import de.lukasrunge.verweil.core.journal.SegmentKind
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayStarted
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

    init {
        // A stay restored from an app version without the journal is missing from the timeline; add it.
        // A paused one is not ongoing on purpose.
        val anchor = engine.stayAnchor
        val latest = journal.latest()
        if (anchor != null && !engine.paused && (latest == null || !(latest.kind == SegmentKind.STAY && latest.ongoing))) {
            journal.record(listOf(StayStarted(anchor, engine.state.staySinceMs)))
        }
    }

    fun process(event: SensorEvent): List<EngineOutput> {
        val resuming = engine.paused
        return step(event.timeMs, force = resuming) {
            val outputs = engine.process(event)
            // The paused stay goes on: on the timeline, too.
            val anchor = engine.stayAnchor
            if (resuming && anchor != null && outputs.none { it is StayEnded }) journal.resumeStay(anchor, engine.state.staySinceMs)
            outputs
        }
    }

    /**
     * Tracking stops for now. An open stay stays open and nothing is sent: it goes on or ends when tracking starts
     * again, see [StayEngine.pause]. Until then the timeline shows it ending at its last evidence.
     */
    fun pause(nowMs: Long): List<EngineOutput> {
        val staying = engine.stayAnchor != null
        return step(maxOf(nowMs, savedAtMs ?: nowMs), force = true) { engine.pause(nowMs) }.also {
            if (staying) journal.pauseStay(engine.state.lastPresenceMs) else journal.endOngoing()
        }
    }

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
