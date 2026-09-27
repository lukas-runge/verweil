package de.lukasrunge.verweil.core.place

import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.GeoPoint

/** A place the user stayed at, recognised again by its Wi-Fi. Never leaves the device. */
data class Place(
    /** 0 until stored. */
    val id: Long,
    val anchor: GeoPoint,
    val fingerprint: Fingerprint,
    val visits: Int,
)

interface PlaceStore {
    fun all(): List<Place>

    /** Stores the place and returns it with its id. */
    fun save(place: Place): Place
}

class InMemoryPlaceStore : PlaceStore {
    private val places = mutableMapOf<Long, Place>()

    override fun all(): List<Place> = places.values.toList()

    override fun save(place: Place): Place {
        val stored = if (place.id == 0L) place.copy(id = places.size + 1L) else place
        places[stored.id] = stored
        return stored
    }
}

/**
 * Wi-Fi place memory: recognises known places, so repeated visits land on the same point,
 * and merges every finished stay into its place, so the anchor gets better with each visit.
 */
class PlaceMemory(private val store: PlaceStore, private val config: EngineConfig) {

    /** The known place this fingerprint belongs to, if one lies near the measured position. */
    fun match(fingerprint: Fingerprint, near: GeoPoint): Place? = store.all()
        .filter { distanceMeters(it.anchor, near) <= config.placeMatchRadiusM }
        .map { it to similarity(it.fingerprint, fingerprint) }
        .filter { (_, score) -> score >= config.placeMatchSimilarity }
        .maxByOrNull { (_, score) -> score }
        ?.first

    /** Adds a finished stay to its place, or remembers a new place. */
    fun learn(center: GeoPoint, fingerprint: Fingerprint): Place {
        val known = match(fingerprint, center)
            ?: return store.save(Place(0, center, fingerprint.strongest(), visits = 1))
        // Capped, so a place that moved (new router, wrong first anchor) can still follow.
        val weight = minOf(known.visits, MAX_WEIGHT).toDouble()
        val anchor = GeoPoint(
            lat = (known.anchor.lat * weight + center.lat) / (weight + 1),
            lon = (known.anchor.lon * weight + center.lon) / (weight + 1),
        )
        val merged = (known.fingerprint.keys + fingerprint.keys).associateWith {
            ((known.fingerprint[it] ?: 0.0) * weight + (fingerprint[it] ?: 0.0)) / (weight + 1)
        }
        return store.save(Place(known.id, anchor, merged.strongest(), known.visits + 1))
    }

    /** Drops access points that hardly ever show up, and bounds the size. */
    private fun Fingerprint.strongest(): Fingerprint =
        entries.filter { it.value >= MIN_SHARE }.sortedByDescending { it.value }.take(MAX_BSSIDS)
            .associate { it.key to it.value }

    private companion object {
        const val MAX_WEIGHT = 20
        const val MIN_SHARE = 0.05
        const val MAX_BSSIDS = 64
    }
}
