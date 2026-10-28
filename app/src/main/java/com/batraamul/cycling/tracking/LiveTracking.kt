package com.batraamul.cycling.tracking

import com.batraamul.cycling.core.GpsFix
import com.batraamul.cycling.core.ModeState

enum class TrackingIssue {
    ACTIVITY_PERMISSION_MISSING,
    NO_STEP_SENSOR,
    LOCATION_PERMISSION_MISSING,
    GPS_DISABLED,
    RIDE_INTERRUPTED,
}

data class LiveRide(
    val rideId: Long,
    val isCalibration: Boolean,
    val rawDistanceM: Double,
    val factor: Double,
    val elapsedMs: Long,
    val activeSinceMs: Long?,
    val movingMs: Long,
    val speedMps: Double?,
    val accuracyM: Float?,
    val fixCount: Int,
    val meanAccuracyM: Double?,
    val route: List<GpsFix>,
) {
    val correctedDistanceM: Double get() = rawDistanceM * factor
    fun elapsedAt(nowMs: Long): Long = elapsedMs + (activeSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)
}

/** Live, in-memory view of the tracker (not yet saved values included), observed by the UI. */
data class LiveStatus(
    val engineRunning: Boolean = false,
    val mode: ModeState = ModeState(),
    val stepSource: StepSourceKind? = null,
    /** Steps detected but not yet written to the database (written in batches to save battery). */
    val pendingSteps: Long = 0,
    val issues: Set<TrackingIssue> = emptySet(),
    val ride: LiveRide? = null,
)
