package de.lukasrunge.verweil.core.track

import de.lukasrunge.verweil.core.model.Fix
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-velocity Kalman filter, one per axis on a local metre grid.
 *
 * A fix's position is off by metres, but its Doppler speed and bearing are good to a fraction of a metre
 * per second. The filter lets the velocity carry the track along the path and averages the position jitter
 * away, without the lag and cut corners of a moving average. It cannot remove an offset that stays the same
 * for many fixes, e.g. from reflections off buildings.
 */
@Serializable
class TrackSmoother(
    private var originLat: Double = 0.0,
    private var originLon: Double = 0.0,
    private var lastTimeMs: Long? = null,
    private val east: Axis = Axis(),
    private val north: Axis = Axis(),
) {
    /**
     * Folds [fix] in and returns the smoothed position at its time, or null for a fix older than the last one.
     * [accelerationNoise] (m²/s³) is how freely the velocity may change between fixes.
     */
    fun update(fix: Fix, accelerationNoise: Double, maxGapMs: Long): Fix? {
        val last = lastTimeMs
        if (last != null && fix.timeMs < last) return null
        if (last == null || fix.timeMs - last > maxGapMs) {
            start(fix)
        } else {
            val dt = (fix.timeMs - last) / 1000.0
            east.predict(dt, accelerationNoise)
            north.predict(dt, accelerationNoise)
            val (x, y) = toLocal(fix)
            val r = positionVariance(fix)
            east.updatePosition(x, r)
            north.updatePosition(y, r)
            velocity(fix)?.let { (ve, vn, rv) ->
                east.updateVelocity(ve, rv)
                north.updateVelocity(vn, rv)
            }
        }
        lastTimeMs = fix.timeMs
        return estimate(fix)
    }

    /** Forgets the track, e.g. after a stay; the next fix starts a new one. */
    fun reset() {
        lastTimeMs = null
    }

    private fun start(fix: Fix) {
        originLat = fix.lat
        originLon = fix.lon
        val r = positionVariance(fix)
        val v = velocity(fix)
        east.start(0.0, v?.first ?: 0.0, r, v?.third ?: UNKNOWN_VELOCITY_VARIANCE)
        north.start(0.0, v?.second ?: 0.0, r, v?.third ?: UNKNOWN_VELOCITY_VARIANCE)
    }

    private fun estimate(fix: Fix): Fix {
        val lat = originLat + north.p / METERS_PER_DEGREE
        val lon = originLon + east.p / (METERS_PER_DEGREE * cos(originLat.toRadians()))
        // Back to a 68 % radius, the unit of Fix.accuracy.
        val accuracy = RADIUS_68 * sqrt((east.pp + north.pp) / 2)
        val speed = sqrt(east.v * east.v + north.v * north.v)
        val bearing = (atan2(east.v, north.v) * 180 / PI + 360) % 360
        return fix.copy(
            lat = lat,
            lon = lon,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing,
            speedAccuracy = RADIUS_68 * sqrt((east.vv + north.vv) / 2),
            bearingAccuracy = null,
        )
    }

    private fun toLocal(fix: Fix): Pair<Double, Double> = Pair(
        (fix.lon - originLon) * METERS_PER_DEGREE * cos(originLat.toRadians()),
        (fix.lat - originLat) * METERS_PER_DEGREE,
    )

    /**
     * The 68 % radius of a circular Gaussian is 1.51 σ, but consecutive position errors are correlated,
     * which the filter assumes they are not. Treating the radius itself as σ keeps it from trusting them too much.
     */
    private fun positionVariance(fix: Fix): Double = fix.accuracy.coerceAtLeast(1.0).let { it * it }

    /** East and north velocity with one variance for both, or null when the fix carries no usable velocity. */
    private fun velocity(fix: Fix): Triple<Double, Double, Double>? {
        val speed = fix.speed ?: return null
        val speedSigma = fix.speedAccuracy ?: DEFAULT_SPEED_ACCURACY
        val bearing = fix.bearing
        if (bearing == null) {
            // No direction: only a standstill says something about both axes.
            if (speed > STANDSTILL_MPS) return null
            val sigma = maxOf(speedSigma, speed)
            return Triple(0.0, 0.0, sigma * sigma)
        }
        val bearingSigma = (fix.bearingAccuracy ?: DEFAULT_BEARING_ACCURACY).toRadians()
        val sigma = sqrt(speedSigma * speedSigma + (speed * bearingSigma).let { it * it })
        val b = bearing.toRadians()
        return Triple(speed * sin(b), speed * cos(b), sigma * sigma)
    }

    /** Position [p] and velocity [v] along one axis with their covariance. */
    @Serializable
    class Axis(
        var p: Double = 0.0,
        var v: Double = 0.0,
        var pp: Double = 0.0,
        var pv: Double = 0.0,
        var vv: Double = 0.0,
    ) {
        fun start(position: Double, velocity: Double, positionVariance: Double, velocityVariance: Double) {
            p = position
            v = velocity
            pp = positionVariance
            pv = 0.0
            vv = velocityVariance
        }

        fun predict(dt: Double, q: Double) {
            p += v * dt
            pp += dt * (2 * pv + dt * vv) + q * dt * dt * dt / 3
            pv += dt * vv + q * dt * dt / 2
            vv += q * dt
        }

        fun updatePosition(z: Double, r: Double) {
            val s = pp + r
            val kp = pp / s
            val kv = pv / s
            val y = z - p
            p += kp * y
            v += kv * y
            vv -= kv * pv
            pv *= 1 - kp
            pp *= 1 - kp
        }

        fun updateVelocity(z: Double, r: Double) {
            val s = vv + r
            val kp = pv / s
            val kv = vv / s
            val y = z - v
            p += kp * y
            v += kv * y
            pp -= kp * pv
            pv *= 1 - kv
            vv *= 1 - kv
        }
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val RADIUS_68 = 1.51
        const val STANDSTILL_MPS = 0.3
        const val DEFAULT_SPEED_ACCURACY = 0.5
        const val DEFAULT_BEARING_ACCURACY = 20.0
        /** A walker's or driver's speed before the first velocity measurement: anything up to about 10 m/s. */
        const val UNKNOWN_VELOCITY_VARIANCE = 100.0

        fun Double.toRadians() = this * PI / 180
    }
}
