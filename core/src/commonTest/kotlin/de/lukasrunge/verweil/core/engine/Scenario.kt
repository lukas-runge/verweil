package de.lukasrunge.verweil.core.engine

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.GeofenceExit
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.model.WifiScan
import de.lukasrunge.verweil.core.upload.PointItem
import de.lukasrunge.verweil.core.upload.toUploadItems
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Duration

/** Builds synthetic sensor days on a local metre grid around a fixed origin. */
class Scenario(private val origin: GeoPoint = GeoPoint(52.5200, 13.4050)) {
    val events = mutableListOf<SensorEvent>()
    var nowMs = 1_790_000_000_000L
        private set
    private var fed = 0

    fun at(eastM: Double, northM: Double): GeoPoint = GeoPoint(
        lat = origin.lat + northM / 111_320.0,
        lon = origin.lon + eastM / (111_320.0 * cos(origin.lat * PI / 180)),
    )

    fun fix(eastM: Double, northM: Double, accuracy: Double = 8.0) {
        val p = at(eastM, northM)
        events += Fix(nowMs, p.lat, p.lon, accuracy)
    }

    fun activity(activity: Activity) {
        events += ActivityChange(nowMs, activity)
    }

    fun wifi(vararg bssids: String) {
        events += WifiScan(nowMs, bssids.toSet())
    }

    fun geofenceExit() {
        events += GeofenceExit(nowMs)
    }

    fun tick() {
        events += Tick(nowMs)
    }

    fun advance(duration: Duration) {
        nowMs += duration.inWholeMilliseconds
    }

    /** Feeds the events added since the last run, so a test can inspect the engine in between. */
    fun run(engine: StayEngine = StayEngine()): List<EngineOutput> {
        val new = events.drop(fed)
        fed = events.size
        return new.flatMap { engine.process(it) }
    }
}

/** Distance as Dawarich computes it: the sum over consecutive uploaded points. */
fun List<EngineOutput>.dawarichDistanceMeters(): Double {
    val points = flatMap { it.toUploadItems() }.filterIsInstance<PointItem>().sortedBy { it.timeMs }
    return points.zipWithNext { a, b -> distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }.sum()
}
