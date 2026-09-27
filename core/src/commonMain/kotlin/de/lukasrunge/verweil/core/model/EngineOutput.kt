package de.lukasrunge.verweil.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** What the engine decided; the upload layer turns this into Dawarich points and visits. */
sealed interface EngineOutput

/** A filtered fix that belongs to a movement track. */
data class TrackPoint(val fix: Fix, val activity: Activity) : EngineOutput

/** A stay was recognised. [sinceMs] is backdated to when it most likely began. */
data class StayStarted(val anchor: GeoPoint, val sinceMs: Long) : EngineOutput

/** Still there: keeps the map populated during long stays. [timeMs] is the latest evidence of presence. */
data class StayHeartbeat(val anchor: GeoPoint, val timeMs: Long) : EngineOutput

/**
 * A stay ended. [untilMs] is the last evidence of presence, not the moment the departure was confirmed.
 * [anchor] is where the stay was pinned when it started; [center] is the best estimate with everything
 * seen during the stay, snapped to a known place when the Wi-Fi matched one.
 */
data class StayEnded(val anchor: GeoPoint, val sinceMs: Long, val untilMs: Long, val center: GeoPoint) : EngineOutput
