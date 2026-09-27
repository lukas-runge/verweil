package de.lukasrunge.verweil.core.geo

import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

private fun Double.toRadians() = this * kotlin.math.PI / 180.0

/** Great-circle distance in metres. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val h = sin(dLat / 2).let { it * it } +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/**
 * Robust centre of a cluster: the per-axis weighted median, weighted by 1/accuracy².
 * Unlike a mean, a few far-off fixes cannot pull it away.
 */
fun weightedMedian(fixes: List<Fix>): GeoPoint {
    require(fixes.isNotEmpty()) { "weightedMedian needs at least one fix" }
    val weights = fixes.map { 1.0 / (it.accuracy.coerceAtLeast(1.0).let { a -> a * a }) }
    return GeoPoint(
        lat = weightedMedianOf(fixes.map { it.lat }, weights),
        lon = weightedMedianOf(fixes.map { it.lon }, weights),
    )
}

private fun weightedMedianOf(values: List<Double>, weights: List<Double>): Double {
    val sorted = values.indices.sortedBy { values[it] }
    val half = weights.sum() / 2
    var acc = 0.0
    for (i in sorted) {
        acc += weights[i]
        if (acc >= half) return values[i]
    }
    return values[sorted.last()]
}
