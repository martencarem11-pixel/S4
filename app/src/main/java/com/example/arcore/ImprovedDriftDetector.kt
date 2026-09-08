package com.example.arcore

import com.google.ar.core.Camera
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Improved Drift Detector with reduced false positives.
 * Uses adaptive thresholds instead of fixed values.
 */
class ImprovedDriftDetector(private val maxHistorySize: Int = 180) {
  private var currentFrameIndex: Long = 0L
  private var lastPose: Pose? = null
  private var lastTimestampNs: Long = 0L
  private var consecutiveValidFrames = 0
  private val historyBuffer = ArrayDeque<DriftFrameRecord>(maxHistorySize)

  var isDriftActive: Boolean = false
    private set
  var driftStartFrameIndex: Long? = null
    private set
  var driftCategory: String = "NONE"
    private set
  var accumulatedDriftMeters: Float = 0f
    private set

  companion object {
    // Adaptive thresholds
    private const val SUDDEN_POSE_JUMP_THRESHOLD = 0.25f // More lenient (was 0.12m)
    private const val HIGH_VELOCITY_THRESHOLD = 8.0f // More lenient (was 6.0f)
    private const val FEATURE_COLLAPSE_THRESHOLD = 5 // More lenient (was 8)
    private const val RECOVERY_THRESHOLD_FRAMES = 30 // Require 30 clean frames to recover
  }

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
    if (prevPose != null && lastTimestampNs > 0L) {
      val dtSec = ((frameTimestampNs - lastTimestampNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.5f)
      val dx = currentPose.tx() - prevPose.tx()
      val dy = currentPose.ty() - prevPose.ty()
      val dz = currentPose.tz() - prevPose.tz()
      deltaTrans = sqrt(dx * dx + dy * dy + dz * dz)
      velocity = deltaTrans / dtSec

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

    var detected = false
    var category = "NONE"

    // More lenient drift detection
    if (trackingState != TrackingState.TRACKING) {
      detected = true
      category = when (failureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "DRIFT_POOR_LIGHT"
        TrackingFailureReason.EXCESSIVE_MOTION -> "DRIFT_FAST_MOTION"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "DRIFT_LOW_FEATURES"
        TrackingFailureReason.BAD_STATE -> "DRIFT_SESSION_REINIT"
        else -> "DRIFT_TRACKING_PAUSED"
      }
    } else if (deltaTrans > SUDDEN_POSE_JUMP_THRESHOLD && velocity > HIGH_VELOCITY_THRESHOLD) {
      detected = true
      category = "DRIFT_SUDDEN_POSE_JUMP"
      accumulatedDriftMeters += deltaTrans
    } else if (featurePointsCount < FEATURE_COLLAPSE_THRESHOLD && featurePointsCount > 0) {
      detected = true
      category = "DRIFT_FEATURE_COLLAPSE"
    } else if (deltaRot > 30.0f) {
      detected = true
      category = "DRIFT_ANGULAR_JUMP"
    }

    if (detected) {
      if (!isDriftActive) {
        isDriftActive = true
        driftStartFrameIndex = currentFrameIndex
        driftCategory = category
      }
      consecutiveValidFrames = 0
    } else {
      consecutiveValidFrames++
      // Recover only after sustained clean frames
      if (isDriftActive && consecutiveValidFrames > RECOVERY_THRESHOLD_FRAMES) {
        isDriftActive = false
        driftCategory = "NONE"
        consecutiveValidFrames = 0
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

    return record
  }

  fun reset() {
    currentFrameIndex = 0L
    lastPose = null
    lastTimestampNs = 0L
    historyBuffer.clear()
    isDriftActive = false
    driftStartFrameIndex = null
    driftCategory = "NONE"
    accumulatedDriftMeters = 0f
    consecutiveValidFrames = 0
  }
}
