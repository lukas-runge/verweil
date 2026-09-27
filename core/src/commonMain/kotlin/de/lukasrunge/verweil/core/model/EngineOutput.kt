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

/** A stay ended. [untilMs] is the last evidence of presence, not the moment the departure was confirmed. */
data class StayEnded(val anchor: GeoPoint, val sinceMs: Long, val untilMs: Long) : EngineOutput
