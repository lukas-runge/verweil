package de.lukasrunge.verweil.core.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
    val minPointSpacingM: Double = 15.0,
    /** A point at the anchor this often during a stay; null turns heartbeats off. */
    val heartbeatInterval: Duration? = 60.minutes,
    /** Wi-Fi scans a stay needs before its fingerprint counts, for departures and place memory. */
    val minWifiScans: Int = 2,
    /** A scan less similar than this to the stay's fingerprint suggests the place changed (`S_leave`). */
    val wifiLeaveSimilarity: Double = 0.3,
    /** A stay at least this similar to a known place is the same place. */
    val placeMatchSimilarity: Double = 0.5,
    /** Guards against moving access points (trains, hotspots): a known place only matches this close by. */
    val placeMatchRadiusM: Double = 250.0,
)
