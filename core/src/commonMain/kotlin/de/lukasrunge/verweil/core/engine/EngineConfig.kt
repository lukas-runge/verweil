package de.lukasrunge.verweil.core.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Thresholds of the stay/move state machine. See docs/concept.md for their meaning. */
data class EngineConfig(
    /** Maximum accuracy radius of a fix that may move the state machine. */
    val goodAccuracyM: Double = 35.0,
    /** Fixes up to this accuracy may still help to place a stay anchor. */
    val anchorAccuracyM: Double = 100.0,
    val stayRadiusM: Double = 75.0,
    val exitRadiusM: Double = 150.0,
    /** While moving, a fix is already outside at `stayRadiusM` plus this many times its accuracy (`F_exit`). */
    val exitAccuracyFactor: Double = 2.0,
    val settleWindow: Duration = 3.minutes,
    val minStay: Duration = 5.minutes,
    val leaveTimeout: Duration = 3.minutes,
    val exitFixesWithoutMotion: Int = 2,
    /**
     * Kalman-smooth track fixes with their Doppler velocity before thinning them. Off: on a recorded walk
     * with fixes every second it moved the track away from the path walked, since the fused provider already
     * filters its fixes and the remaining error is an offset over many seconds. Kept for replays.
     */
    val smoothTrack: Boolean = false,
    /** How freely the smoother lets the velocity change, in m²/s³; higher follows turns faster but keeps more jitter. */
    val smoothingAccelerationNoise: Double = 1.0,
    /** A longer silence between track fixes starts the smoother afresh. */
    val smoothingMaxGap: Duration = 30.seconds,
    /** Track points closer than this to the line between their neighbours are dropped (`D_simplify`); null thins by [minPointSpacingM]. */
    val simplifyToleranceM: Double? = 3.0,
    /** Longest time between two track points, so straight stretches still show their speed (`T_point`). */
    val maxTrackPointInterval: Duration = 30.seconds,
    /** Spacing of track points when [simplifyToleranceM] is null (`D_min`). */
    val minPointSpacingM: Double = 15.0,
    /**
     * A point at the anchor this often during a stay; null turns heartbeats off. Dawarich's own visit detection
     * needs 3 points per stay and ends a stay after an hour without points; with a point every 5 minutes it finds
     * every stay itself, instead of replacing Verweil's suggested visit with nothing.
     */
    val heartbeatInterval: Duration? = 5.minutes,
    /**
     * A stay paused by stopping tracking goes on if tracking starts again this soon after its last evidence of
     * presence; later, it ends at that evidence. Dawarich bridges such a silence at the same place itself.
     */
    val resumeWindow: Duration = 1.hours,
    /** Wi-Fi scans a stay needs before its fingerprint counts, for departures and place memory. */
    val minWifiScans: Int = 2,
    /** A scan less similar than this to the stay's fingerprint suggests the place changed (`S_leave`). */
    val wifiLeaveSimilarity: Double = 0.3,
    /** A stay at least this similar to a known place is the same place. */
    val placeMatchSimilarity: Double = 0.5,
    /** Guards against moving access points (trains, hotspots): a known place only matches this close by. */
    val placeMatchRadiusM: Double = 250.0,
)
