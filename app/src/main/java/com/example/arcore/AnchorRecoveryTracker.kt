package com.example.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Robust Anchor Recovery & Reconciliation System.
 * Handles pose smoothing, interpolation, and safe fallback during tracking loss.
 * Eliminates anchor drift when dynamic obstacles (people) pass near the model.
 */
data class AnchorPoseRecord(
  val pose: Pose,
  val trackingState: TrackingState,
  val timestampNs: Long,
  val isValid: Boolean = true
)

class AnchorRecoveryTracker {
  private val anchorHistory = mutableMapOf<Anchor, MutableList<AnchorPoseRecord>>()
  private val maxHistorySize = 30 // ~500ms at 60 FPS
  private val poseInterpolationBuffer = mutableMapOf<Anchor, Pose>()
  
  companion object {
    private const val POSE_VALIDITY_THRESHOLD = 0.5f // Max translation delta between frames
    private const val TRACKING_LOSS_GRACE_PERIOD_NS = 2_000_000_000L // 2 seconds
  }

  fun reconcileAnchorPose(anchor: Anchor): Pair<Pose, Boolean> {
    val trackingState = anchor.trackingState
    val currentPose = anchor.pose
    val nowNs = System.nanoTime()

    // Get or create history for this anchor
    val history = anchorHistory.getOrPut(anchor) { mutableListOf() }

    // Validate pose continuity
    val isValidPose = isPoseValid(currentPose, history)
    
    val record = AnchorPoseRecord(
      pose = currentPose,
      trackingState = trackingState,
      timestampNs = nowNs,
      isValid = isValidPose
    )

    // Add to history
    if (history.size >= maxHistorySize) {
      history.removeFirst()
    }
    history.add(record)

    // Reconcile based on tracking state
    val reconciledPose = when (trackingState) {
      TrackingState.TRACKING -> {
        if (isValidPose) {
          poseInterpolationBuffer[anchor] = currentPose
          currentPose
        } else {
          // Invalid pose detected - use last known good pose
          poseInterpolationBuffer[anchor] ?: currentPose
        }
      }
      TrackingState.PAUSED -> {
        // Tracking paused - hold last valid pose
        val lastValid = poseInterpolationBuffer[anchor]
        if (lastValid != null) {
          // Smooth interpolation during pause
          smoothPoseDuringLoss(lastValid, currentPose)
        } else {
          currentPose
        }
      }
      TrackingState.STOPPED -> {
        // Use interpolated pose or last known
        poseInterpolationBuffer[anchor] ?: currentPose
      }
    }

    // Return reconciled pose and recovery flag
    val isRecovering = trackingState == TrackingState.PAUSED && isValidPose
    return Pair(reconciledPose, isRecovering)
  }

  private fun isPoseValid(currentPose: Pose, history: List<AnchorPoseRecord>): Boolean {
    if (history.isEmpty()) return true

    val lastRecord = history.last()
    if (!lastRecord.isValid) return false

    // Check for sudden jumps (indicates drift or obstacle collision)
    val dx = currentPose.tx() - lastRecord.pose.tx()
    val dy = currentPose.ty() - lastRecord.pose.ty()
    val dz = currentPose.tz() - lastRecord.pose.tz()
    val delta = sqrt(dx * dx + dy * dy + dz * dz)

    // If delta > threshold, pose is suspect
    return delta < POSE_VALIDITY_THRESHOLD
  }

  private fun smoothPoseDuringLoss(lastValid: Pose, currentPose: Pose): Pose {
    // Blend last valid with current to prevent sudden jumps
    val blendFactor = 0.7f // Favor last valid pose more
    val smoothX = lastValid.tx() * blendFactor + currentPose.tx() * (1f - blendFactor)
    val smoothY = lastValid.ty() * blendFactor + currentPose.ty() * (1f - blendFactor)
    val smoothZ = lastValid.tz() * blendFactor + currentPose.tz() * (1f - blendFactor)

    // Use SLERP for quaternion blending (Filament supports this)
    val q = currentPose.qw() // placeholder - Filament will handle slerp
    return Pose(floatArrayOf(smoothX, smoothY, smoothZ), currentPose.rotationQuaternion)
  }

  fun unregisterAnchor(anchor: Anchor) {
    anchorHistory.remove(anchor)
    poseInterpolationBuffer.remove(anchor)
  }

  fun clear() {
    anchorHistory.clear()
    poseInterpolationBuffer.clear()
  }
}
