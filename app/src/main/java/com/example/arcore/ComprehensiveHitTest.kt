package com.example.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose

enum class ComprehensiveHitType {
  STABLE_PLANE_HORIZONTAL,
  STABLE_PLANE_VERTICAL,
  STABLE_PLANE_EXTENTS,
  DEPTH_POINT_CLOUD,
  DEPTH_IMAGE_MAP_SAMPLING,
  INSTANT_PLACEMENT_FULL,
  INSTANT_PLACEMENT_TENTATIVE,
  ORIENTED_FEATURE_POINT,
  GENERIC_FEATURE_POINT
}

data class ComprehensiveHitResult(
  val hitResult: HitResult?,
  val hitPose: Pose,
  val hitType: ComprehensiveHitType,
  val distanceMeters: Float,
  val isInstantTentative: Boolean,
  val plane: Plane? = null,
  val confidenceScore: Float = 1.0f
)
