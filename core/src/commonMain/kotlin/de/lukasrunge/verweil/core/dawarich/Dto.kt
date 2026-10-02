package de.lukasrunge.verweil.core.dawarich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Overland batch format as parsed by Dawarich's app/services/overland/params.rb.

@Serializable
internal data class OverlandBatch(val locations: List<OverlandFeature>)

@Serializable
internal data class OverlandFeature(
    val type: String = "Feature",
    val geometry: PointGeometry,
    val properties: OverlandProperties,
)

@Serializable
internal data class PointGeometry(
    val type: String = "Point",
    /** GeoJSON order: longitude, latitude. */
    val coordinates: List<Double>,
)

@Serializable
internal data class OverlandProperties(
    val timestamp: String,
    @SerialName("horizontal_accuracy") val horizontalAccuracy: Double? = null,
    val speed: Double? = null,
    val altitude: Double? = null,
    val motion: List<String>? = null,
    /** 1.0: the motion is certain (needs a Dawarich that knows the field; others ignore it). */
    @SerialName("motion_confidence") val motionConfidence: Double? = null,
    @SerialName("device_id") val deviceId: String? = null,
)

/** Dawarich's placeholder name for a visit at a place it has not named yet; not a name to show. */
internal const val SUGGESTED_PLACE = "Suggested place"

// Mobile auth API: app/controllers/api/v1/auth/{sessions,otp_challenges,base}_controller.rb.

@Serializable
internal data class LoginRequest(val email: String, val password: String)

@Serializable
internal data class OtpRequest(
    @SerialName("challenge_token") val challengeToken: String,
    @SerialName("otp_code") val otpCode: String,
)

@Serializable
internal data class AuthSuccess(
    val email: String,
    @SerialName("api_key") val apiKey: String,
)

@Serializable
internal data class OtpChallenge(
    @SerialName("challenge_token") val challengeToken: String,
    val ttl: Int = 300,
)

@Serializable
internal data class AuthError(val message: String? = null)

// GET /api/v1/users/me: app/serializers/api/user_serializer.rb.

@Serializable
internal data class MeResponse(val user: MeUser)

@Serializable
internal data class MeUser(val email: String)

/** Content of the QR code in Dawarich under Account → API access. */
@Serializable
data class ConnectionCode(
    @SerialName("server_url") val serverUrl: String,
    @SerialName("api_key") val apiKey: String,
)

// GET /api/v1/timeline (Dawarich 1.3 and later): app/services/timeline/day_assembler.rb.

@Serializable
internal data class TimelineResponse(val days: List<TimelineDayDto> = emptyList())

@Serializable
internal data class TimelineDayDto(val date: String, val entries: List<TimelineEntryDto> = emptyList())

/** A visit or a journey; which one is in [type]. Fields of the other kind stay null. */
@Serializable
internal data class TimelineEntryDto(
    val type: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    // Visits
    @SerialName("visit_id") val visitId: Long? = null,
    val name: String? = null,
    val status: String? = null,
    val place: TimelinePlaceDto? = null,
    val area: TimelineAreaDto? = null,
    /** The tags of the visit's place. */
    val tags: List<TimelineTagDto> = emptyList(),
    // Journeys
    @SerialName("track_id") val trackId: Long? = null,
    /** In [distanceUnit], rounded to 0.1. */
    val distance: Double? = null,
    @SerialName("distance_unit") val distanceUnit: String? = null,
    @SerialName("dominant_mode") val dominantMode: String? = null,
    /** Set on the second day of a journey across midnight: that day's share of the distance. */
    @SerialName("day_distance") val dayDistance: Double? = null,
)

@Serializable
internal data class TimelineTagDto(val name: String, val icon: String? = null, val color: String? = null)

@Serializable
internal data class TimelinePlaceDto(val name: String? = null, val lat: Double? = null, val lng: Double? = null)

@Serializable
internal data class TimelineAreaDto(val name: String? = null, val lat: Double? = null, val lng: Double? = null)

// GET and PATCH /api/v1/tracks/:track_id/segments: app/controllers/api/v1/tracks/segments_controller.rb.

@Serializable
internal data class TrackSegmentsDto(
    @SerialName("dominant_mode") val dominantMode: String? = null,
    @SerialName("enabled_modes") val enabledModes: List<String> = emptyList(),
    val segments: List<TrackSegmentDto> = emptyList(),
)

@Serializable
internal data class TrackSegmentDto(
    val id: Long,
    @SerialName("transportation_mode") val transportationMode: String,
    /** Null on old segments Dawarich anchored by point index only. */
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    /** Metres. */
    val distance: Double? = null,
)

@Serializable
internal data class SegmentModeRequest(@SerialName("transportation_mode") val transportationMode: String)
