package de.lukasrunge.verweil.core.upload

import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayHeartbeat
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.TrackPoint

/** A point as it ends up in Dawarich. */
data class PointItem(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val altitude: Double? = null,
    /** Overland motion value: stationary, walking, running, cycling, driving. */
    val motion: String? = null,
)

/**
 * Translates engine decisions into the points Dawarich gets.
 * A stay becomes points at its anchor (arrival, a heartbeat every 5 minutes, departure, so 0 km in between).
 * Verweil sends no visits: Dawarich detects the stay from these points itself (see docs/concept.md).
 */
fun EngineOutput.toUploadItems(): List<PointItem> = when (this) {
    is TrackPoint -> listOf(
        PointItem(
            timeMs = fix.timeMs,
            lat = fix.lat,
            lon = fix.lon,
            accuracy = fix.accuracy,
            speed = fix.speed,
            altitude = fix.altitude,
            motion = activity.overlandMotion(),
        ),
    )

    is StayStarted -> listOf(
        PointItem(timeMs = sinceMs, lat = anchor.lat, lon = anchor.lon, motion = STATIONARY),
    )

    is StayHeartbeat -> listOf(
        PointItem(timeMs = timeMs, lat = anchor.lat, lon = anchor.lon, motion = STATIONARY),
    )

    is StayEnded -> listOf(
        PointItem(timeMs = untilMs, lat = anchor.lat, lon = anchor.lon, motion = STATIONARY),
    )
}

private const val STATIONARY = "stationary"

private fun Activity.overlandMotion(): String? = when (this) {
    Activity.STILL -> STATIONARY
    Activity.WALKING -> "walking"
    Activity.RUNNING -> "running"
    Activity.CYCLING -> "cycling"
    Activity.VEHICLE -> "driving"
    Activity.UNKNOWN -> null
}
