package de.lukasrunge.verweil.core.place

import kotlinx.serialization.Serializable

/** Hashed BSSID → share of scans it appeared in (0..1). */
typealias Fingerprint = Map<String, Double>

/** A single scan as a fingerprint: everything in it was seen once out of once. */
fun Set<String>.asFingerprint(): Fingerprint = associateWith { 1.0 }

/** Weighted Jaccard similarity: 1 for identical fingerprints, 0 for nothing in common. */
fun similarity(a: Fingerprint, b: Fingerprint): Double {
    var shared = 0.0
    var total = 0.0
    for (bssid in a.keys + b.keys) {
        val x = a[bssid] ?: 0.0
        val y = b[bssid] ?: 0.0
        shared += minOf(x, y)
        total += maxOf(x, y)
    }
    return if (total == 0.0) 0.0 else shared / total
}

/** Counts how often each access point showed up across the Wi-Fi scans of one stay. */
@Serializable
class WifiTally(
    var scans: Int = 0,
    val seen: MutableMap<String, Int> = mutableMapOf(),
) {
    fun add(bssids: Set<String>) {
        scans++
        bssids.forEach { seen[it] = (seen[it] ?: 0) + 1 }
    }

    fun clear() {
        scans = 0
        seen.clear()
    }

    /** The stay's fingerprint, once it rests on at least [minScans] scans. */
    fun fingerprint(minScans: Int): Fingerprint? =
        if (scans < minScans || seen.isEmpty()) null else seen.mapValues { it.value.toDouble() / scans }
}
