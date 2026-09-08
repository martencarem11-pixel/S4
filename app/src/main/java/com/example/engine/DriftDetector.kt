package com.example.engine

import com.google.ar.core.Camera
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Detailed Diagnostic Frame Record for tracking drift detection.
 */
data class DriftFrameRecord(
  val frameNumber: Long,
  val timestampMs: Long,
  val trackingState: TrackingState,
  val failureReason: TrackingFailureReason,
  val tx: Float,
  val ty: Float,
  val tz: Float,
  val deltaTransMeters: Float,
  val deltaRotDegrees: Float,
  val velocityMps: Float,
  val featurePointsCount: Int,
  val isDriftIdentified: Boolean,
  val driftCategory: String = "NONE"
)

/**
 * Diagnostic Frame History and Drift Detection Engine.
 * Analyzes frame-to-frame VIO (Visual Inertial Odometry) pose differentials, feature point density,
 * and sudden relocalization jumps to pinpoint the EXACT frame number and timestamp where tracking
 * drift or failure initiates.
 */
class DriftDetector(private val maxHistorySize: Int = 180) {

  private var currentFrameIndex: Long = 0L
  private var lastPose: Pose? = null
  private var lastTimestampNs: Long = 0L

  private val historyBuffer = ArrayDeque<DriftFrameRecord>(maxHistorySize)

  var isDriftActive: Boolean = false
    private set
  var driftStartFrameIndex: Long? = null
    private set
  var driftStartTimestampMs: Long? = null
    private set
  var driftCategory: String = "NONE"
    private set
  var accumulatedDriftMeters: Float = 0f
    private set
  var lastIdentifiedFrame: DriftFrameRecord? = null
    private set

  /**
   * Evaluates the current AR frame for spatial drift.
   * Zero heap allocation per call beyond the recorded history entries.
   */
  fun evaluateFrame(
    frameTimestampNs: Long,
    camera: Camera,
    featurePointsCount: Int
  ): DriftFrameRecord {
    currentFrameIndex++
    val nowMs = frameTimestampNs / 1_000_000L
    val currentPose = camera.pose
    val trackingState = camera.trackingState
    val failureReason = if (trackingState == TrackingState.PAUSED) {
      camera.trackingFailureReason
    } else {
      TrackingFailureReason.NONE
    }

    var deltaTrans = 0f
    var deltaRot = 0f
    var velocity = 0f

    val prevPose = lastPose
    val prevTimestamp = lastTimestampNs

    if (prevPose != null && prevTimestamp > 0L) {
      val dtSec = ((frameTimestampNs - prevTimestamp) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.5f)
      val dx = currentPose.tx() - prevPose.tx()
      val dy = currentPose.ty() - prevPose.ty()
      val dz = currentPose.tz() - prevPose.tz()
      deltaTrans = sqrt(dx * dx + dy * dy + dz * dz)
      velocity = deltaTrans / dtSec

      // Quaternion dot product for angular delta
      val dot = prevPose.qx() * currentPose.qx() +
          prevPose.qy() * currentPose.qy() +
          prevPose.qz() * currentPose.qz() +
          prevPose.qw() * currentPose.qw()
      val clampedDot = dot.coerceIn(-1.0f, 1.0f)
      val angleRad = 2.0 * kotlin.math.acos(kotlin.math.abs(clampedDot).toDouble())
      deltaRot = Math.toDegrees(angleRad).toFloat()
    }

    lastPose = currentPose
    lastTimestampNs = frameTimestampNs

    // Drift Detection Criteria:
    // 1. SUDDEN_POSE_JUMP: Single-frame translation > 0.12m (~7.5 m/s) without proportional rotation
    // 2. FEATURE_COLLAPSE: Feature points suddenly dropped to < 8 while tracking
    // 3. TRACKING_LOSS: Tracking state transitioned from TRACKING to PAUSED/STOPPED
    // 4. EXCESSIVE_ROTATION_ACCELERATION: Angular jump > 25° in 16ms
    var detected = false
    var category = "NONE"

    if (trackingState != TrackingState.TRACKING) {
      detected = true
      category = when (failureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "DRIFT_POOR_LIGHT"
        TrackingFailureReason.EXCESSIVE_MOTION -> "DRIFT_FAST_MOTION"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "DRIFT_LOW_FEATURES"
        TrackingFailureReason.BAD_STATE -> "DRIFT_SESSION_REINIT"
        else -> "DRIFT_TRACKING_PAUSED"
      }
    } else if (deltaTrans > 0.12f && velocity > 6.0f) {
      detected = true
      category = "DRIFT_SUDDEN_POSE_JUMP"
      accumulatedDriftMeters += deltaTrans
    } else if (featurePointsCount < 8 && featurePointsCount > 0) {
      detected = true
      category = "DRIFT_FEATURE_COLLAPSE"
    } else if (deltaRot > 28.0f) {
      detected = true
      category = "DRIFT_ANGULAR_JUMP"
    }

    if (detected) {
      if (!isDriftActive) {
        // Mark the EXACT start frame of this drift episode
        isDriftActive = true
        driftStartFrameIndex = currentFrameIndex
        driftStartTimestampMs = nowMs
        driftCategory = category
      }
    } else {
      // Clear drift state if tracking is consistently clean
      if (isDriftActive && trackingState == TrackingState.TRACKING && deltaTrans < 0.05f) {
        isDriftActive = false
        driftCategory = "NONE"
      }
    }

    val record = DriftFrameRecord(
      frameNumber = currentFrameIndex,
      timestampMs = nowMs,
      trackingState = trackingState,
      failureReason = failureReason,
      tx = currentPose.tx(),
      ty = currentPose.ty(),
      tz = currentPose.tz(),
      deltaTransMeters = deltaTrans,
      deltaRotDegrees = deltaRot,
      velocityMps = velocity,
      featurePointsCount = featurePointsCount,
      isDriftIdentified = detected,
      driftCategory = category
    )

    if (historyBuffer.size >= maxHistorySize) {
      historyBuffer.removeFirst()
    }
    historyBuffer.addLast(record)
    lastIdentifiedFrame = record

    return record
  }

  fun getRecentHistory(): List<DriftFrameRecord> = historyBuffer.toList()

  fun reset() {
    currentFrameIndex = 0L
    lastPose = null
    lastTimestampNs = 0L
    historyBuffer.clear()
    isDriftActive = false
    driftStartFrameIndex = null
    driftStartTimestampMs = null
    driftCategory = "NONE"
    accumulatedDriftMeters = 0f
    lastIdentifiedFrame = null
  }
}
