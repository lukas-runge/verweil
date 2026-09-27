package de.lukasrunge.verweil.core.upload

import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayHeartbeat
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.TrackPoint

/** What ends up in Dawarich. */
sealed interface UploadItem

data class PointItem(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val altitude: Double? = null,
    /** Overland motion value: stationary, walking, running, cycling, driving. */
    val motion: String? = null,
) : UploadItem

data class VisitItem(
    val lat: Double,
    val lon: Double,
    val startedMs: Long,
    val endedMs: Long,
) : UploadItem

/**
 * Translates engine decisions into Dawarich data.
 * A stay becomes points at its anchor (arrival, a heartbeat every 5 minutes, departure, so 0 km in between)
 * plus a visit at its refined centre.
 */
fun EngineOutput.toUploadItems(): List<UploadItem> = when (this) {
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
        VisitItem(lat = center.lat, lon = center.lon, startedMs = sinceMs, endedMs = untilMs),
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
