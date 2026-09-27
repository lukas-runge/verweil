package de.lukasrunge.verweil.core.place

import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlinx.serialization.json.Json

class SqlPlaceStore(database: VerweilDatabase) : PlaceStore {
    private val queries = database.placeQueries

    fun count(): Long = queries.count().executeAsOne()

    /** Forgets every known place; the engine learns them anew from the next stays. */
    fun clear() = queries.deleteAll()

    override fun all(): List<Place> = queries.all().executeAsList().map {
        Place(it.id, GeoPoint(it.lat, it.lon), Json.decodeFromString<Fingerprint>(it.fingerprint), it.visits.toInt())
    }

    override fun save(place: Place): Place = queries.transactionWithResult {
        val fingerprint = Json.encodeToString<Fingerprint>(place.fingerprint)
        if (place.id == 0L) {
            queries.insert(place.anchor.lat, place.anchor.lon, fingerprint, place.visits.toLong())
            place.copy(id = queries.lastInsertId().executeAsOne())
        } else {
            queries.update(place.anchor.lat, place.anchor.lon, fingerprint, place.visits.toLong(), place.id)
            place
        }
    }
}
