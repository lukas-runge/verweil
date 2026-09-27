package de.lukasrunge.verweil.core.track

import de.lukasrunge.verweil.core.engine.Scenario
import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackSmootherTest {

    private val grid = Scenario()
    private val random = Random(7)

    @Test
    fun jitterAveragesOutWhileTheDopplerVelocityCarriesTheTrack() {
        val truth = walk(listOf(0.0 to 0.0, 150.0 to 0.0))
        val (raw, smoothed) = run(truth, noiseM = 4.0)

        val rawError = rms(raw, truth)
        val smoothedError = rms(smoothed, truth)
        assertTrue(smoothedError < rawError * 0.5, "raw $rawError m, smoothed $smoothedError m")
    }

    @Test
    fun aCornerIsFollowedNotCut() {
        val truth = walk(listOf(0.0 to 0.0, 60.0 to 0.0, 60.0 to 60.0))
        val (raw, smoothed) = run(truth, noiseM = 3.0)

        // Around the corner, where a moving average would cut across.
        val corner = truth.indices.filter { distanceMeters(truth[it].second, grid.at(60.0, 0.0)) < 10 }
        val rawError = rms(raw.slice(corner), truth.slice(corner))
        val smoothedError = rms(smoothed.slice(corner), truth.slice(corner))
        assertTrue(smoothedError < rawError, "raw $rawError m, smoothed $smoothedError m")
    }

    @Test
    fun anOlderFixIsIgnored() {
        val smoother = TrackSmoother()
        smoother.update(fixAt(10_000, 0.0), ACCELERATION_NOISE, MAX_GAP_MS)

        assertNull(smoother.update(fixAt(9_000, 5.0), ACCELERATION_NOISE, MAX_GAP_MS))
    }

    @Test
    fun aLongGapStartsAFreshTrack() {
        val smoother = TrackSmoother()
        smoother.update(fixAt(0, 0.0), ACCELERATION_NOISE, MAX_GAP_MS)
        val after = fixAt(MAX_GAP_MS + 1_000, 500.0)

        val smoothed = smoother.update(after, ACCELERATION_NOISE, MAX_GAP_MS)!!

        assertEquals(0.0, distanceMeters(smoothed.point, after.point), 0.01)
    }

    /** Walks the corners at 1.4 m/s, one true position per second with its bearing. */
    private fun walk(corners: List<Pair<Double, Double>>): List<Pair<Double, GeoPoint>> {
        val out = mutableListOf<Pair<Double, GeoPoint>>()
        corners.zipWithNext { (e0, n0), (e1, n1) ->
            val length = sqrt((e1 - e0) * (e1 - e0) + (n1 - n0) * (n1 - n0))
            val bearing = (kotlin.math.atan2(e1 - e0, n1 - n0) * 180 / PI + 360) % 360
            val steps = (length / 1.4).toInt()
            repeat(steps) { i ->
                val f = i.toDouble() / steps
                out += bearing to grid.at(e0 + (e1 - e0) * f, n0 + (n1 - n0) * f)
            }
        }
        return out
    }

    /** Raw fixes with Gaussian position noise and slightly noisy Doppler, and what the smoother makes of them. */
    private fun run(truth: List<Pair<Double, GeoPoint>>, noiseM: Double): Pair<List<GeoPoint>, List<GeoPoint>> {
        val smoother = TrackSmoother()
        val raw = mutableListOf<GeoPoint>()
        val smoothed = mutableListOf<GeoPoint>()
        truth.forEachIndexed { i, (bearing, point) ->
            val north = gaussian() * noiseM
            val east = gaussian() * noiseM
            val noisy = GeoPoint(
                point.lat + north / 111_320.0,
                point.lon + east / (111_320.0 * cos(point.lat * PI / 180)),
            )
            val fix = Fix(
                timeMs = i * 1_000L,
                lat = noisy.lat,
                lon = noisy.lon,
                accuracy = noiseM * 1.51,
                speed = 1.4 + gaussian() * 0.1,
                bearing = bearing + gaussian() * 5,
                speedAccuracy = 0.2,
                bearingAccuracy = 10.0,
            )
            raw += noisy
            smoothed += smoother.update(fix, ACCELERATION_NOISE, MAX_GAP_MS)!!.point
        }
        return raw to smoothed
    }

    private fun rms(points: List<GeoPoint>, truth: List<Pair<Double, GeoPoint>>): Double =
        sqrt(points.zip(truth).sumOf { (p, t) -> distanceMeters(p, t.second).let { it * it } } / points.size)

    private fun fixAt(timeMs: Long, eastM: Double): Fix {
        val p = grid.at(eastM, 0.0)
        return Fix(timeMs, p.lat, p.lon, accuracy = 8.0)
    }

    /** Box–Muller; kotlin.random has no normal distribution. */
    private fun gaussian(): Double = sqrt(-2 * ln(1 - random.nextDouble())) * cos(2 * PI * random.nextDouble())

    private companion object {
        const val ACCELERATION_NOISE = 1.0
        const val MAX_GAP_MS = 30_000L
    }
}
