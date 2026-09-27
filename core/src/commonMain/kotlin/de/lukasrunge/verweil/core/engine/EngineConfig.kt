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
    val settleWindow: Duration = 3.minutes,
    val minStay: Duration = 5.minutes,
    val leaveTimeout: Duration = 3.minutes,
    val exitFixesWithoutMotion: Int = 2,
    val minPointSpacingM: Double = 15.0,
)
