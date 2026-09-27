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
    @SerialName("device_id") val deviceId: String? = null,
)

// Visits API: app/controllers/api/v1/visits_controller.rb#visit_params.

@Serializable
internal data class VisitRequest(val visit: VisitBody)

@Serializable
internal data class VisitBody(
    val latitude: Double,
    val longitude: Double,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    /** Dawarich requires a name; with status "suggested" it reverse-geocodes a real one for new places. */
    val name: String = "Suggested place",
    val status: String = "suggested",
)

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
