package de.lukasrunge.verweil.core.engine

import de.lukasrunge.verweil.core.geo.distanceMeters
import de.lukasrunge.verweil.core.geo.weightedMedian
import de.lukasrunge.verweil.core.model.Activity
import de.lukasrunge.verweil.core.model.ActivityChange
import de.lukasrunge.verweil.core.model.EngineOutput
import de.lukasrunge.verweil.core.model.Fix
import de.lukasrunge.verweil.core.model.GeoPoint
import de.lukasrunge.verweil.core.model.SensorEvent
import de.lukasrunge.verweil.core.model.StayEnded
import de.lukasrunge.verweil.core.model.StayStarted
import de.lukasrunge.verweil.core.model.Tick
import de.lukasrunge.verweil.core.model.TrackPoint
import de.lukasrunge.verweil.core.model.WifiScan

enum class Mode { MOVING, SETTLING, STAYING, LEAVING }

/**
 * Decides whether the user stays or moves, and only lets movement through as track points.
 *
 * Deterministic and clock-free: feed it events in time order, get decisions back.
 * The state machine is described in docs/concept.md.
 */
class StayEngine(private val config: EngineConfig = EngineConfig()) {

    var mode: Mode = Mode.MOVING
        private set

    private var activity = Activity.UNKNOWN

    // Plausibility reference and track spacing.
    private var lastAccepted: Fix? = null
    private var lastForwarded: Fix? = null
    private var implausibleInARow = 0

    /** Good fixes of the last settle window while moving; used to detect stays without a STILL signal. */
    private val recent = ArrayDeque<Fix>()

    // SETTLING
    private var settleSinceMs = 0L
    private val anchorCandidates = mutableListOf<Fix>()
    private val bufferedTrack = mutableListOf<Fix>()
    private var outsideInARow = 0

    // STAYING and LEAVING
    private var anchor: GeoPoint? = null
    private var staySinceMs = 0L
    private var lastPresenceMs = 0L
    private var leaveSinceMs = 0L
    private val exitFixes = mutableListOf<Fix>()
    private val leavingTrack = mutableListOf<Fix>()

    fun process(event: SensorEvent): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        when (event) {
            is Fix -> onFix(event, out)
            is ActivityChange -> onActivity(event, out)
            is WifiScan -> Unit // Place memory is not implemented yet.
            is Tick -> Unit
        }
        onTime(event.timeMs, out)
        if (mode == Mode.STAYING) lastPresenceMs = event.timeMs
        return out
    }

    private fun onFix(fix: Fix, out: MutableList<EngineOutput>) {
        when (mode) {
            Mode.MOVING -> {
                if (!fix.isGood() || !isPlausible(fix)) return
                lastAccepted = fix
                forwardIfSpaced(fix, out)
                rememberRecent(fix)
                if (activity == Activity.STILL) {
                    startSettling(fix.timeMs, listOf(fix))
                } else if (recentFormsCluster()) {
                    startSettling(recent.first().timeMs, recent.toList())
                }
            }

            Mode.SETTLING -> {
                if (fix.accuracy > config.anchorAccuracyM) return
                if (fix.isGood() && anchorCandidates.isNotEmpty()) {
                    val center = weightedMedian(anchorCandidates)
                    if (distanceMeters(center, fix.point) > config.stayRadiusM) {
                        outsideInARow++
                        if (activity != Activity.STILL || outsideInARow >= config.exitFixesWithoutMotion) {
                            abortSettling(fix, out)
                        }
                        return
                    }
                    outsideInARow = 0
                }
                anchorCandidates += fix
                if (fix.isGood()) {
                    bufferedTrack += fix
                    lastAccepted = fix
                }
            }

            Mode.STAYING -> {
                if (!fix.isGood()) return
                if (distanceMeters(anchor!!, fix.point) > config.exitRadiusM) {
                    startLeaving(fix.timeMs, fix, out)
                }
            }

            Mode.LEAVING -> {
                if (!fix.isGood()) return
                if (distanceMeters(anchor!!, fix.point) > config.exitRadiusM) {
                    exitFixes += fix
                    confirmLeavingIfPossible(out)
                } else if (activity.isMoving) {
                    // Walking away but not out yet: keep the fix in case the departure is confirmed.
                    exitFixes.clear()
                    leavingTrack += fix
                } else {
                    lastPresenceMs = fix.timeMs
                    backToStaying()
                }
            }
        }
    }

    private fun onActivity(event: ActivityChange, out: MutableList<EngineOutput>) {
        activity = event.activity
        when (mode) {
            Mode.MOVING -> if (activity == Activity.STILL) {
                val seed = lastAccepted?.takeIf { event.timeMs - it.timeMs <= config.settleWindow.inWholeMilliseconds }
                startSettling(event.timeMs, listOfNotNull(seed))
            }

            Mode.SETTLING -> Unit
            Mode.STAYING -> if (activity.isMoving) startLeaving(event.timeMs, null, out)
            Mode.LEAVING -> if (activity == Activity.STILL && exitFixes.isEmpty()) {
                backToStaying()
            } else {
                confirmLeavingIfPossible(out)
            }
        }
    }

    private fun onTime(nowMs: Long, out: MutableList<EngineOutput>) {
        when (mode) {
            Mode.SETTLING -> if (nowMs - settleSinceMs >= config.minStay.inWholeMilliseconds && anchorCandidates.isNotEmpty()) {
                val stayAnchor = weightedMedian(anchorCandidates)
                anchor = stayAnchor
                staySinceMs = settleSinceMs
                lastPresenceMs = nowMs
                mode = Mode.STAYING
                out += StayStarted(stayAnchor, settleSinceMs)
            }

            Mode.LEAVING -> if (nowMs - leaveSinceMs >= config.leaveTimeout.inWholeMilliseconds) {
                // Not confirmed in time: it was jitter or a walk within the building.
                backToStaying()
            }

            Mode.MOVING, Mode.STAYING -> Unit
        }
    }

    private fun startSettling(sinceMs: Long, seed: List<Fix>) {
        mode = Mode.SETTLING
        settleSinceMs = sinceMs
        anchorCandidates.clear()
        anchorCandidates += seed
        bufferedTrack.clear()
        outsideInARow = 0
        recent.clear()
    }

    /** The candidate was only a short stop: release what was held back and keep moving. */
    private fun abortSettling(trigger: Fix, out: MutableList<EngineOutput>) {
        mode = Mode.MOVING
        bufferedTrack.forEach { forwardIfSpaced(it, out) }
        bufferedTrack.clear()
        lastAccepted = trigger
        forwardIfSpaced(trigger, out)
        rememberRecent(trigger)
    }

    private fun startLeaving(timeMs: Long, firstExitFix: Fix?, out: MutableList<EngineOutput>) {
        mode = Mode.LEAVING
        leaveSinceMs = timeMs
        exitFixes.clear()
        leavingTrack.clear()
        if (firstExitFix != null) exitFixes += firstExitFix
        confirmLeavingIfPossible(out)
    }

    private fun backToStaying() {
        mode = Mode.STAYING
        exitFixes.clear()
        leavingTrack.clear()
    }

    private fun confirmLeavingIfPossible(out: MutableList<EngineOutput>) {
        if (exitFixes.isEmpty()) return
        if (!activity.isMoving && exitFixes.size < config.exitFixesWithoutMotion) return

        val stayAnchor = anchor!!
        out += StayEnded(stayAnchor, staySinceMs, lastPresenceMs)

        // The new track starts at the anchor; the stay-ended point already marks it.
        mode = Mode.MOVING
        anchor = null
        lastForwarded = Fix(lastPresenceMs, stayAnchor.lat, stayAnchor.lon, accuracy = 0.0)
        (leavingTrack + exitFixes).sortedBy { it.timeMs }.forEach { forwardIfSpaced(it, out) }
        lastAccepted = exitFixes.last()
        exitFixes.clear()
        leavingTrack.clear()
        recent.clear()
    }

    private fun forwardIfSpaced(fix: Fix, out: MutableList<EngineOutput>) {
        val last = lastForwarded
        if (last == null || distanceMeters(last.point, fix.point) >= config.minPointSpacingM) {
            out += TrackPoint(fix, activity)
            lastForwarded = fix
        }
    }

    private fun rememberRecent(fix: Fix) {
        recent.addLast(fix)
        val windowStart = fix.timeMs - config.settleWindow.inWholeMilliseconds
        // Keep exactly one fix at or before the window start, so we know the window is fully covered.
        while (recent.size >= 2 && recent[1].timeMs <= windowStart) recent.removeFirst()
    }

    private fun recentFormsCluster(): Boolean {
        val first = recent.firstOrNull() ?: return false
        val last = recent.last()
        if (last.timeMs - first.timeMs < config.settleWindow.inWholeMilliseconds) return false
        val center = weightedMedian(recent)
        return recent.all { distanceMeters(center, it.point) <= config.stayRadiusM }
    }

    /**
     * Rejects fixes that would need an implausible speed from the last accepted one.
     * After a few rejections in a row the reference itself was probably wrong, so the fix is accepted.
     */
    private fun isPlausible(fix: Fix): Boolean {
        val ref = lastAccepted ?: return true
        val seconds = (fix.timeMs - ref.timeMs) / 1000.0
        if (seconds <= 0) return false
        val plausible = distanceMeters(ref.point, fix.point) / seconds <= maxSpeedMps(activity)
        implausibleInARow = if (plausible) 0 else implausibleInARow + 1
        if (implausibleInARow >= 3) {
            implausibleInARow = 0
            return true
        }
        return plausible
    }

    private fun Fix.isGood() = accuracy > 0 && accuracy <= config.goodAccuracyM

    private fun maxSpeedMps(activity: Activity): Double = when (activity) {
        Activity.WALKING, Activity.RUNNING -> 12.0
        Activity.CYCLING -> 25.0
        else -> 90.0
    }
}
