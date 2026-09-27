package de.lukasrunge.verweil.core.engine

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.geo.weightedMedian
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.GeofenceExit
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayHeartbeat
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.model.TrackPoint
import de.lukasrunge.verweil.core.model.WifiScan
import de.lukasrunge.verweil.core.place.InMemoryPlaceStore
import de.lukasrunge.verweil.core.place.PlaceMemory
import de.lukasrunge.verweil.core.place.WifiTally
import de.lukasrunge.verweil.core.place.asFingerprint
import de.lukasrunge.verweil.core.place.similarity
import kotlinx.serialization.Serializable

enum class Mode { MOVING, SETTLING, STAYING, LEAVING }

/**
 * Everything the engine remembers between events.
 * Serializable, so a restarted app continues an ongoing stay instead of forgetting it.
 */
@Serializable
class EngineState(
    var mode: Mode = Mode.MOVING,
    var activity: Activity = Activity.UNKNOWN,

    // Plausibility reference and track spacing.
    var lastAccepted: Fix? = null,
    var lastForwarded: Fix? = null,
    var implausibleInARow: Int = 0,

    /** Good fixes of the last settle window while moving; used to detect stays without a STILL signal. */
    val recent: MutableList<Fix> = mutableListOf(),

    // SETTLING
    var settleSinceMs: Long = 0,
    val anchorCandidates: MutableList<Fix> = mutableListOf(),
    val bufferedTrack: MutableList<Fix> = mutableListOf(),
    var outsideInARow: Int = 0,
    /** Wi-Fi seen since settling began. */
    val wifi: WifiTally = WifiTally(),

    // STAYING and LEAVING
    var anchor: GeoPoint? = null,
    var staySinceMs: Long = 0,
    var lastPresenceMs: Long = 0,
    var lastHeartbeatMs: Long = 0,
    /** Fixes during the stay; together with the anchor candidates they refine the stay's centre. */
    val stayFixes: MutableList<Fix> = mutableListOf(),
    var leaveSinceMs: Long = 0,
    val exitFixes: MutableList<Fix> = mutableListOf(),
    val leavingTrack: MutableList<Fix> = mutableListOf(),
)

/**
 * Decides whether the user stays or moves, and only lets movement through as track points.
 *
 * Deterministic and clock-free: feed it events in time order, get decisions back.
 * The state machine is described in docs/concept.md.
 */
class StayEngine(
    private val config: EngineConfig = EngineConfig(),
    private val places: PlaceMemory = PlaceMemory(InMemoryPlaceStore(), config),
    /** A saved [state] to continue from. */
    initialState: EngineState = EngineState(),
) {
    var state: EngineState = initialState
        private set

    private val s get() = state

    val mode: Mode get() = s.mode

    /** Where the current stay is pinned, while there is one. */
    val stayAnchor: GeoPoint? get() = s.anchor.takeIf { s.mode == Mode.STAYING || s.mode == Mode.LEAVING }

    fun process(event: SensorEvent): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        when (event) {
            is Fix -> onFix(event, out)
            is ActivityChange -> onActivity(event, out)
            is WifiScan -> onWifi(event, out)
            is GeofenceExit -> if (s.mode == Mode.STAYING) startLeaving(event.timeMs, null, out)
            is Tick -> Unit
        }
        onTime(event.timeMs, out)
        return out
    }

    /**
     * Tracking stops: closes an open stay at its last evidence of presence and releases held-back
     * track points. Whatever comes next starts from scratch.
     */
    fun finish(): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        when (s.mode) {
            Mode.SETTLING -> s.bufferedTrack.forEach { forwardIfSpaced(it, out) }
            Mode.STAYING, Mode.LEAVING -> endStay(out)
            Mode.MOVING -> Unit
        }
        state = EngineState()
        return out
    }

    private fun onFix(fix: Fix, out: MutableList<EngineOutput>) {
        when (s.mode) {
            Mode.MOVING -> {
                if (!fix.isGood() || !isPlausible(fix)) return
                s.lastAccepted = fix
                forwardIfSpaced(fix, out)
                rememberRecent(fix)
                if (s.activity == Activity.STILL) {
                    startSettling(fix.timeMs, listOf(fix))
                } else if (recentFormsCluster()) {
                    startSettling(s.recent.first().timeMs, s.recent.toList())
                }
            }

            Mode.SETTLING -> {
                if (fix.accuracy > config.anchorAccuracyM) return
                if (fix.isGood() && s.anchorCandidates.isNotEmpty()) {
                    val center = weightedMedian(s.anchorCandidates)
                    if (distanceMeters(center, fix.point) > config.stayRadiusM) {
                        s.outsideInARow++
                        if (s.activity != Activity.STILL || s.outsideInARow >= config.exitFixesWithoutMotion) {
                            abortSettling(fix, out)
                        }
                        return
                    }
                    s.outsideInARow = 0
                }
                s.anchorCandidates += fix
                if (fix.isGood()) {
                    s.bufferedTrack += fix
                    s.lastAccepted = fix
                }
            }

            Mode.STAYING -> {
                if (fix.isGood() && isOutside(fix)) {
                    startLeaving(fix.timeMs, fix, out)
                } else if (distanceMeters(s.anchor!!, fix.point) <= config.exitRadiusM && fix.accuracy <= config.anchorAccuracyM) {
                    present(fix.timeMs)
                    rememberStayFix(fix)
                }
            }

            Mode.LEAVING -> {
                if (!fix.isGood()) return
                if (isOutside(fix)) {
                    s.exitFixes += fix
                    confirmLeavingIfPossible(out)
                } else if (s.activity.isMoving) {
                    // Walking away but not out yet: keep the fix in case the departure is confirmed.
                    s.exitFixes.clear()
                    s.leavingTrack += fix
                } else {
                    present(fix.timeMs)
                    rememberStayFix(fix)
                    backToStaying()
                }
            }
        }
    }

    private fun onActivity(event: ActivityChange, out: MutableList<EngineOutput>) {
        s.activity = event.activity
        when (s.mode) {
            Mode.MOVING -> if (s.activity == Activity.STILL) {
                val seed = s.lastAccepted?.takeIf { event.timeMs - it.timeMs <= config.settleWindow.inWholeMilliseconds }
                startSettling(event.timeMs, listOfNotNull(seed))
            }

            Mode.SETTLING -> Unit
            Mode.STAYING -> when {
                s.activity.isMoving -> startLeaving(event.timeMs, null, out)
                s.activity == Activity.STILL -> present(event.timeMs)
                else -> Unit
            }

            Mode.LEAVING -> if (s.activity == Activity.STILL && s.exitFixes.isEmpty()) {
                backToStaying()
            } else {
                confirmLeavingIfPossible(out)
            }
        }
    }

    private fun onWifi(scan: WifiScan, out: MutableList<EngineOutput>) {
        // Wi-Fi off or nothing around: no evidence either way.
        if (scan.bssids.isEmpty()) return
        when (s.mode) {
            Mode.SETTLING -> s.wifi.add(scan.bssids)
            Mode.STAYING -> {
                val known = s.wifi.fingerprint(config.minWifiScans)
                if (known == null) {
                    s.wifi.add(scan.bssids)
                } else if (similarity(scan.bssids.asFingerprint(), known) < config.wifiLeaveSimilarity) {
                    startLeaving(scan.timeMs, null, out)
                } else {
                    s.wifi.add(scan.bssids)
                    present(scan.timeMs)
                }
            }

            // Wi-Fi reaches into the street, so it neither confirms nor cancels a departure.
            Mode.MOVING, Mode.LEAVING -> Unit
        }
    }

    private fun onTime(nowMs: Long, out: MutableList<EngineOutput>) {
        when (s.mode) {
            Mode.SETTLING -> if (nowMs - s.settleSinceMs >= config.minStay.inWholeMilliseconds && s.anchorCandidates.isNotEmpty()) {
                startStay(nowMs, out)
            }

            Mode.STAYING -> {
                val interval = config.heartbeatInterval?.inWholeMilliseconds ?: return
                if (s.lastPresenceMs - s.lastHeartbeatMs >= interval) {
                    out += StayHeartbeat(s.anchor!!, s.lastPresenceMs)
                    s.lastHeartbeatMs = s.lastPresenceMs
                }
            }

            Mode.LEAVING -> if (nowMs - s.leaveSinceMs >= config.leaveTimeout.inWholeMilliseconds) {
                // Not confirmed in time: it was jitter or a walk within the building.
                backToStaying()
            }

            Mode.MOVING -> Unit
        }
    }

    private fun startSettling(sinceMs: Long, seed: List<Fix>) {
        s.mode = Mode.SETTLING
        s.settleSinceMs = sinceMs
        s.anchorCandidates.clear()
        s.anchorCandidates += seed
        s.bufferedTrack.clear()
        s.outsideInARow = 0
        s.recent.clear()
        s.wifi.clear()
    }

    /** The candidate was only a short stop: release what was held back and keep moving. */
    private fun abortSettling(trigger: Fix, out: MutableList<EngineOutput>) {
        s.mode = Mode.MOVING
        s.bufferedTrack.forEach { forwardIfSpaced(it, out) }
        s.bufferedTrack.clear()
        s.lastAccepted = trigger
        forwardIfSpaced(trigger, out)
        rememberRecent(trigger)
    }

    private fun startStay(nowMs: Long, out: MutableList<EngineOutput>) {
        val measured = weightedMedian(s.anchorCandidates)
        // A known place pins the stay on the very point of earlier visits.
        val place = s.wifi.fingerprint(minScans = 1)?.let { places.match(it, measured) }
        val anchor = place?.anchor ?: measured
        s.mode = Mode.STAYING
        s.anchor = anchor
        s.staySinceMs = s.settleSinceMs
        s.lastPresenceMs = nowMs
        s.lastHeartbeatMs = s.settleSinceMs
        s.stayFixes.clear()
        out += StayStarted(anchor, s.settleSinceMs)
    }

    private fun startLeaving(timeMs: Long, firstExitFix: Fix?, out: MutableList<EngineOutput>) {
        s.mode = Mode.LEAVING
        s.leaveSinceMs = timeMs
        s.exitFixes.clear()
        s.leavingTrack.clear()
        if (firstExitFix != null) s.exitFixes += firstExitFix
        confirmLeavingIfPossible(out)
    }

    private fun backToStaying() {
        s.mode = Mode.STAYING
        s.exitFixes.clear()
        s.leavingTrack.clear()
    }

    private fun confirmLeavingIfPossible(out: MutableList<EngineOutput>) {
        if (s.exitFixes.isEmpty()) return
        if (!s.activity.isMoving && s.exitFixes.size < config.exitFixesWithoutMotion) return

        val stayAnchor = s.anchor!!
        endStay(out)

        // The new track starts at the anchor; the stay-ended point already marks it.
        s.mode = Mode.MOVING
        s.anchor = null
        s.lastForwarded = Fix(s.lastPresenceMs, stayAnchor.lat, stayAnchor.lon, accuracy = 0.0)
        (s.leavingTrack + s.exitFixes).sortedBy { it.timeMs }.forEach { forwardIfSpaced(it, out) }
        s.lastAccepted = s.exitFixes.last()
        s.exitFixes.clear()
        s.leavingTrack.clear()
        s.recent.clear()
    }

    /** Emits the end of the stay with its refined centre and teaches the place memory about it. */
    private fun endStay(out: MutableList<EngineOutput>) {
        val anchor = s.anchor!!
        val evidence = s.anchorCandidates + s.stayFixes
        val measured = if (evidence.isEmpty()) anchor else weightedMedian(evidence)
        val place = s.wifi.fingerprint(config.minWifiScans)?.let { places.learn(measured, it) }
        out += StayEnded(anchor, s.staySinceMs, s.lastPresenceMs, center = place?.anchor ?: measured)
    }

    /** Presence only moves forward: platforms may deliver a stale event late, e.g. the last activity on restart. */
    private fun present(timeMs: Long) {
        s.lastPresenceMs = maxOf(s.lastPresenceMs, timeMs)
    }

    private fun rememberStayFix(fix: Fix) {
        s.stayFixes += fix
        // Bounded for long stays: keep the most accurate ones, they dominate the weighted median anyway.
        if (s.stayFixes.size > MAX_STAY_FIXES) {
            val best = s.stayFixes.sortedBy { it.accuracy }.take(MAX_STAY_FIXES / 2).sortedBy { it.timeMs }
            s.stayFixes.clear()
            s.stayFixes += best
        }
    }

    private fun forwardIfSpaced(fix: Fix, out: MutableList<EngineOutput>) {
        val last = s.lastForwarded
        if (last == null || distanceMeters(last.point, fix.point) >= config.minPointSpacingM) {
            out += TrackPoint(fix, s.activity)
            s.lastForwarded = fix
        }
    }

    private fun rememberRecent(fix: Fix) {
        s.recent.add(fix)
        val windowStart = fix.timeMs - config.settleWindow.inWholeMilliseconds
        // Keep exactly one fix at or before the window start, so we know the window is fully covered.
        while (s.recent.size >= 2 && s.recent[1].timeMs <= windowStart) s.recent.removeAt(0)
    }

    private fun recentFormsCluster(): Boolean {
        val first = s.recent.firstOrNull() ?: return false
        val last = s.recent.last()
        if (last.timeMs - first.timeMs < config.settleWindow.inWholeMilliseconds) return false
        val center = weightedMedian(s.recent)
        return s.recent.all { distanceMeters(center, it.point) <= config.stayRadiusM }
    }

    /**
     * Rejects fixes that would need an implausible speed from the last accepted one.
     * After a few rejections in a row the reference itself was probably wrong, so the fix is accepted.
     */
    private fun isPlausible(fix: Fix): Boolean {
        val ref = s.lastAccepted ?: return true
        val seconds = (fix.timeMs - ref.timeMs) / 1000.0
        if (seconds <= 0) return false
        val plausible = distanceMeters(ref.point, fix.point) / seconds <= maxSpeedMps(s.activity)
        s.implausibleInARow = if (plausible) 0 else s.implausibleInARow + 1
        if (s.implausibleInARow >= 3) {
            s.implausibleInARow = 0
            return true
        }
        return plausible
    }

    /**
     * Whether a fix counts as outside the stay. While moving, a precise fix is already out just beyond the
     * stay radius, so a walk around the block becomes a trip; otherwise it takes `R_exit`, against indoor jitter.
     */
    private fun isOutside(fix: Fix): Boolean {
        val radius = if (s.activity.isMoving) {
            minOf(config.exitRadiusM, config.stayRadiusM + config.exitAccuracyFactor * fix.accuracy)
        } else {
            config.exitRadiusM
        }
        return distanceMeters(s.anchor!!, fix.point) > radius
    }

    private fun Fix.isGood() = accuracy > 0 && accuracy <= config.goodAccuracyM

    private fun maxSpeedMps(activity: Activity): Double = when (activity) {
        Activity.WALKING, Activity.RUNNING -> 12.0
        Activity.CYCLING -> 25.0
        else -> 90.0
    }

    private companion object {
        const val MAX_STAY_FIXES = 240
    }
}
