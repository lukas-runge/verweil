package de.lukasrunge.verweil

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import de.lukasrunge.verweil.core.journal.Segment
import de.lukasrunge.verweil.core.journal.SegmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Names stays in the timeline with the phone's geocoder (on most phones Google's), which receives their
 * coordinates. Only for display on the phone; Dawarich names its visits itself.
 */
class PlaceNames(context: Context, private val app: VerweilApp) {
    private val geocoder = Geocoder(context)

    /** Stays already asked about while this screen is open, so a failed lookup is not repeated on every update. */
    private val attempted = mutableSetOf<Long>()

    /** Looks up names for stays that have none yet and stores them with the segment. */
    suspend fun fillIn(segments: List<Segment>) {
        if (!Geocoder.isPresent()) return
        segments.filter { it.kind == SegmentKind.STAY && it.placeName == null && it.id !in attempted }.forEach { stay ->
            attempted += stay.id
            // Empty when nothing was found, so the same stay is not asked about again.
            val name = lookUp(stay.point.lat, stay.point.lon) ?: return
            withContext(Dispatchers.IO) { app.journal.setPlaceName(stay.id, name) }
        }
    }

    /** Null when the lookup failed and may work later, e.g. offline. */
    private suspend fun lookUp(lat: Double, lon: Double): String? = try {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(
                    lat,
                    lon,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = continuation.resume(null)
                    },
                )
            }
        } else {
            @Suppress("DEPRECATION") // The listener variant needs Android 13.
            withContext(Dispatchers.IO) { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }
        }
        address?.shortName().orEmpty()
    } catch (_: IOException) {
        null
    }
}

/** "Hauptstraße 5, Berlin": street and house number, then the town. */
private fun Address.shortName(): String {
    val street = thoroughfare?.let { street -> subThoroughfare?.let { "$street $it" } ?: street }
    return listOfNotNull(street ?: featureName, locality).distinct().joinToString(", ")
}
