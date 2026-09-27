package de.lukasrunge.verweil.core.replay

import de.lukasrunge.verweil.core.engine.EngineConfig
import de.lukasrunge.verweil.core.engine.StayEngine
import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.upload.PointItem
import de.lukasrunge.verweil.core.upload.toUploadItems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Replays a raw recording through variants of the track pipeline and writes each result as GeoJSON,
 * to compare them on a map (e.g. geojson.io) and by numbers, without walking the route again.
 *
 * ./gradlew :core:replay --args="recording.jsonl [--from 13:30] [--to 13:40] [--truth route.geojson] [--out dir]"
 *
 * The truth is the route actually walked, drawn as a LineString; with it, each variant gets its offset from it.
 */
fun main(args: Array<String>) {
    val options = parseOptions(args.toList())
    val input = File(options.getValue("input"))
    val events = EventLog.decode(input.readLines().asSequence()).sortedBy { it.timeMs }.toList()
    require(events.isNotEmpty()) { "No events in $input" }
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(events.first().timeMs).atZone(zone).toLocalDate()
    fun at(time: String?, default: Long) =
        time?.let { day.atTime(LocalTime.parse(it)).atZone(zone).toInstant().toEpochMilli() } ?: default
    val window = at(options["from"], Long.MIN_VALUE)..at(options["to"], Long.MAX_VALUE)
    val truth = options["truth"]?.let { readLine(File(it)) }
    val out = File(options["out"] ?: "replay-out").apply { mkdirs() }

    val app = EngineConfig()
    val spacing = app.copy(smoothTrack = false, simplifyToleranceM = null)
    val simplified = app.copy(smoothTrack = false, simplifyToleranceM = app.simplifyToleranceM ?: 3.0)
    val smoothed = app.copy(smoothTrack = true, simplifyToleranceM = null, minPointSpacingM = 0.0)
    val smoothedSimplified = simplified.copy(smoothTrack = true)
    val variants = listOf(
        Variant("raw", "#999999") { raw(events, app) },
        Variant("before-5s-spacing", "#e41a1c") { engine(everyFiveSeconds(events), spacing) },
        Variant("spacing", "#ff7f00") { engine(events, spacing) },
        Variant("simplified-5s", "#fb9a99") { engine(everyFiveSeconds(events), simplified) },
        Variant("simplified", "#984ea3", isApp = simplified == app) { engine(events, simplified) },
        Variant("smoothed", "#4daf4a") { engine(events, smoothed) },
        Variant("smoothed-simplified", "#377eb8", isApp = smoothedSimplified == app) { engine(events, smoothedSimplified) },
    )

    val doppler = dopplerDistance(events.filterIsInstance<Fix>().filter { it.timeMs in window })
    println("Window ${Instant.ofEpochMilli(maxOf(window.first, events.first().timeMs)).atZone(zone).toLocalTime()}" +
        "–${Instant.ofEpochMilli(minOf(window.last, events.last().timeMs)).atZone(zone).toLocalTime()}, " +
        "Doppler distance ${"%.0f".format(doppler)} m" + (truth?.let { ", truth ${"%.0f".format(length(it))} m" } ?: ""))
    println("%-24s %7s %9s %11s %11s %9s".format("variant (* app)", "points", "length", "mean off", "p95 off", "max off"))

    val all = mutableListOf<JsonElement>()
    for (variant in variants) {
        val points = variant.run().filter { it.timeMs in window }.sortedBy { it.timeMs }
        val offsets = truth?.let { line -> densify(points.map { it.point }).map { offsetFrom(line, it) }.sorted() }
        println(
            "%-24s %7d %8.0fm %11s %11s %9s".format(
                variant.name + if (variant.isApp) " *" else "",
                points.size,
                length(points.map { it.point }),
                offsets?.let { "%.1fm".format(it.average()) } ?: "-",
                offsets?.let { "%.1fm".format(it[(it.size * 0.95).toInt().coerceAtMost(it.size - 1)]) } ?: "-",
                offsets?.let { "%.1fm".format(it.last()) } ?: "-",
            ),
        )
        val features = features(variant, points)
        File(out, "${variant.name}.geojson").writeText(collection(features).toString())
        all += features
    }
    truth?.let { all += lineFeature("truth", "#000000", it) }
    File(out, "all.geojson").writeText(collection(all).toString())
    println("GeoJSON in ${out.absolutePath}")
}

private class Variant(val name: String, val color: String, val isApp: Boolean = false, val run: () -> List<Point>)

private data class Point(val timeMs: Long, val point: GeoPoint, val accuracy: Double?)

/** Every good fix, the way it came from the phone. */
private fun raw(events: List<SensorEvent>, config: EngineConfig) = events.filterIsInstance<Fix>()
    .filter { it.accuracy <= config.goodAccuracyM }
    .map { Point(it.timeMs, it.point, it.accuracy) }

/** What Dawarich would get: every point the engine uploads, including those of stays. */
private fun engine(events: List<SensorEvent>, config: EngineConfig): List<Point> {
    val engine = StayEngine(config)
    val outputs = events.flatMap { engine.process(it) } + engine.finish()
    return outputs.flatMap { it.toUploadItems() }.filterIsInstance<PointItem>()
        .map { Point(it.timeMs, GeoPoint(it.lat, it.lon), it.accuracy) }
}

/** The recording as if the phone had delivered a fix only every 5 s, like before. */
private fun everyFiveSeconds(events: List<SensorEvent>): List<SensorEvent> {
    var last: Long? = null
    return events.filter { event ->
        if (event !is Fix) return@filter true
        val keep = last.let { it == null || event.timeMs - it >= 4_500 }
        if (keep) last = event.timeMs
        keep
    }
}

/** Distance from the Doppler speed; close to the true length of a walk and independent of position jitter. */
private fun dopplerDistance(fixes: List<Fix>): Double = fixes.zipWithNext { a, b ->
    val dt = (b.timeMs - a.timeMs) / 1000.0
    if (dt > 10) 0.0 else (b.speed ?: 0.0) * dt
}.sum()

private fun length(points: List<GeoPoint>) = points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

/** Every metre along the track, so a long straight segment through a detour counts for its whole length. */
private fun densify(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 2) return points
    return points.zipWithNext { a, b ->
        val steps = distanceMeters(a, b).toInt().coerceAtLeast(1)
        (0 until steps).map { i ->
            val f = i.toDouble() / steps
            GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
    }.flatten() + points.last()
}

private fun offsetFrom(line: List<GeoPoint>, p: GeoPoint): Double = line.zipWithNext { a, b ->
    val scale = cos(a.lat * PI / 180) * 111_320.0
    val bx = (b.lon - a.lon) * scale
    val by = (b.lat - a.lat) * 111_320.0
    val px = (p.lon - a.lon) * scale
    val py = (p.lat - a.lat) * 111_320.0
    val length2 = bx * bx + by * by
    val t = if (length2 == 0.0) 0.0 else ((px * bx + py * by) / length2).coerceIn(0.0, 1.0)
    hypot(px - t * bx, py - t * by)
}.minOrNull() ?: distanceMeters(line.first(), p)

/** The first LineString in a GeoJSON file, as drawn on geojson.io. */
private fun readLine(file: File): List<GeoPoint> {
    fun find(element: JsonElement): JsonArray? {
        val obj = element as? JsonObject ?: return null
        return when (obj["type"]?.jsonPrimitive?.content) {
            "LineString" -> obj["coordinates"]!!.jsonArray
            "MultiLineString" -> JsonArray(obj["coordinates"]!!.jsonArray.flatMap { it.jsonArray })
            "Feature" -> obj["geometry"]?.let(::find)
            "FeatureCollection" -> obj["features"]!!.jsonArray.firstNotNullOfOrNull(::find)
            else -> null
        }
    }
    val coordinates = find(Json.parseToJsonElement(file.readText())) ?: error("No LineString in $file")
    return coordinates.map { c -> c.jsonArray.let { GeoPoint(lat = it[1].jsonPrimitive.double, lon = it[0].jsonPrimitive.double) } }
}

private fun features(variant: Variant, points: List<Point>): List<JsonElement> = buildList {
    add(lineFeature(variant.name, variant.color, points.map { it.point }))
    points.forEach { p ->
        add(
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") {
                        add(JsonPrimitive(p.point.lon))
                        add(JsonPrimitive(p.point.lat))
                    }
                }
                putJsonObject("properties") {
                    put("variant", variant.name)
                    put("time", Instant.ofEpochMilli(p.timeMs).atZone(ZoneId.systemDefault()).toLocalTime().toString())
                    p.accuracy?.let { put("accuracy", "%.1f".format(it)) }
                    put("marker-color", variant.color)
                    put("marker-size", "small")
                }
            },
        )
    }
}

private fun lineFeature(name: String, color: String, points: List<GeoPoint>) = buildJsonObject {
    put("type", "Feature")
    putJsonObject("geometry") {
        put("type", "LineString")
        putJsonArray("coordinates") {
            points.forEach { add(buildJsonArray { add(JsonPrimitive(it.lon)); add(JsonPrimitive(it.lat)) }) }
        }
    }
    putJsonObject("properties") {
        put("variant", name)
        put("stroke", color)
        put("stroke-width", 3)
    }
}

private fun collection(features: List<JsonElement>) = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(features))
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            options[arg.removePrefix("--")] = args.getOrNull(i + 1) ?: error("$arg needs a value")
            i += 2
        } else {
            options["input"] = arg
            i++
        }
    }
    require("input" in options) {
        "Usage: replay recording.jsonl [--from HH:mm] [--to HH:mm] [--truth route.geojson] [--out dir]"
    }
    return options
}
