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
