package de.lukasrunge.verweil.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the engine reacts to. Time is epoch milliseconds and comes only from events,
 * so a recorded day replays exactly like it happened.
 */
@Serializable
sealed interface SensorEvent {
    val timeMs: Long
}

@Serializable
@SerialName("fix")
data class Fix(
    override val timeMs: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy radius in metres (68 % confidence). */
    val accuracy: Double,
    /** Metres per second, from the Doppler shift; far more precise than the change in position. */
    val speed: Double? = null,
    val altitude: Double? = null,
    /** Direction of travel in degrees clockwise from north. */
    val bearing: Double? = null,
    /** Metres per second (68 % confidence). */
    val speedAccuracy: Double? = null,
    /** Degrees (68 % confidence). */
    val bearingAccuracy: Double? = null,
) : SensorEvent {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

@Serializable
@SerialName("activity")
data class ActivityChange(
    override val timeMs: Long,
    val activity: Activity,
) : SensorEvent

@Serializable
@SerialName("wifi")
data class WifiScan(
    override val timeMs: Long,
    /** Hashed BSSIDs; raw BSSIDs never leave the platform layer. */
    val bssids: Set<String>,
) : SensorEvent

/** The phone connected to a car (Android Auto, or a Bluetooth device the user marked as their car) or disconnected. */
@Serializable
@SerialName("car")
data class CarConnection(override val timeMs: Long, val connected: Boolean) : SensorEvent

/** The platform saw the phone leave the geofence around a stay anchor. A hint to look closer, not proof. */
@Serializable
@SerialName("geofence_exit")
data class GeofenceExit(override val timeMs: Long) : SensorEvent

/** Lets the platform advance time when no sensor delivers anything, so timeouts still fire. */
@Serializable
@SerialName("tick")
data class Tick(override val timeMs: Long) : SensorEvent

enum class Activity {
    STILL,
    WALKING,
    RUNNING,
    CYCLING,
    VEHICLE,
    UNKNOWN;

    val isMoving: Boolean get() = this == WALKING || this == RUNNING || this == CYCLING || this == VEHICLE
}
