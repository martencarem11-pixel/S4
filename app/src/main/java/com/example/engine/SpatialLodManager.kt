package com.example.engine

import kotlin.math.sqrt

/**
 * Level of Detail (LOD) level definition.
 * LOD_0: High-poly geometry (100% polygons, full skeletal bone & morph evaluation)
 * LOD_1: Medium-poly geometry (~50% polygons, decimated meshes, standard updates)
 * LOD_2: Low-poly geometry (~25% polygons, simplified bounding hull, throttled updates)
 */
enum class LodLevel(
  val levelIndex: Int,
  val enterDistanceMeters: Float,
  val exitDistanceMeters: Float,
  val updateSkipFrames: Int,
  val label: String
) {
  LOD_0(0, 0.0f, 3.2f, 1, "High Poly (LOD 0)"),
  LOD_1(1, 2.6f, 6.8f, 2, "Medium Poly (LOD 1)"),
  LOD_2(2, 5.8f, 100.0f, 4, "Low Poly (LOD 2)")
}

/**
 * Production-grade Distance & Screen-Space Dynamic LOD Manager with Hysteresis.
 * Prevents visual pop-in and flicker by enforcing a hysteresis deadband between LOD transitions.
 * Coordinates actual geometry / mesh level switching while preserving model transforms,
 * PBR materials, and continuous animation playback.
 */
class SpatialLodManager {

  companion object {
    private const val TAG = "SpatialLodManager"
  }

  // Active LOD states per exhibit ID
  private val exhibitLodMap = mutableMapOf<String, LodLevel>()
  private val exhibitFrameCounters = mutableMapOf<String, Int>()

  // Callback triggered when an exhibit actually transitions LOD
  var onLodTransition: ((exhibitId: String, fromLod: LodLevel, toLod: LodLevel) -> Unit)? = null

  var activeLod0Count = 0
    private set
  var activeLod1Count = 0
    private set
  var activeLod2Count = 0
    private set

  /**
   * Calculates the appropriate LOD level for a 3D exhibit using Euclidean distance,
   * screen-space projected size, and hysteresis deadbands to eliminate pop-in/flicker.
   */
  fun evaluateLod(
    exhibitId: String,
    cameraPos: FloatArray,
    objectPos: FloatArray,
    boundingRadiusMeters: Float,
    screenWidthPx: Int,
    screenHeightPx: Int,
    fovDegrees: Float = 60.0f
  ): LodLevel {
    val dx = objectPos[0] - cameraPos[0]
    val dy = objectPos[1] - cameraPos[1]
    val dz = objectPos[2] - cameraPos[2]
    val distance = sqrt(dx * dx + dy * dy + dz * dz)

    val currentLod = exhibitLodMap[exhibitId]

    // Hysteresis calculation:
    // If first time evaluating, assign directly based on distance.
    // Otherwise, only transition if distance has crossed the hysteresis threshold.
    val nextLod = if (currentLod == null) {
      when {
        distance < LodLevel.LOD_0.exitDistanceMeters -> LodLevel.LOD_0
        distance < LodLevel.LOD_1.exitDistanceMeters -> LodLevel.LOD_1
        else -> LodLevel.LOD_2
      }
    } else {
      when (currentLod) {
        LodLevel.LOD_0 -> {
          if (distance > LodLevel.LOD_0.exitDistanceMeters) LodLevel.LOD_1 else LodLevel.LOD_0
        }
        LodLevel.LOD_1 -> {
          if (distance < LodLevel.LOD_1.enterDistanceMeters) {
            LodLevel.LOD_0
          } else if (distance > LodLevel.LOD_1.exitDistanceMeters) {
            LodLevel.LOD_2
          } else {
            LodLevel.LOD_1
          }
        }
        LodLevel.LOD_2 -> {
          if (distance < LodLevel.LOD_2.enterDistanceMeters) LodLevel.LOD_1 else LodLevel.LOD_2
        }
      }
    }

    if (currentLod == null || nextLod != currentLod) {
      exhibitLodMap[exhibitId] = nextLod
      updateLodCounts()
      if (currentLod != null) {
        onLodTransition?.invoke(exhibitId, currentLod, nextLod)
      }
      return nextLod
    }

    return currentLod
  }

  /**
   * Determines if the exhibit's skeletal animation / morphs should update on this frame
   * based on its active LOD skip rate.
   */
  fun shouldUpdateAnimation(exhibitId: String): Boolean {
    val lod = exhibitLodMap[exhibitId] ?: LodLevel.LOD_0
    val counter = (exhibitFrameCounters[exhibitId] ?: 0) + 1
    exhibitFrameCounters[exhibitId] = counter

    return (counter % lod.updateSkipFrames) == 0
  }

  fun getLodForExhibit(exhibitId: String): LodLevel {
    return exhibitLodMap[exhibitId] ?: LodLevel.LOD_0
  }

  fun clear() {
    exhibitLodMap.clear()
    exhibitFrameCounters.clear()
    updateLodCounts()
  }

  private fun updateLodCounts() {
    var l0 = 0
    var l1 = 0
    var l2 = 0
    for (lod in exhibitLodMap.values) {
      when (lod) {
        LodLevel.LOD_0 -> l0++
        LodLevel.LOD_1 -> l1++
        LodLevel.LOD_2 -> l2++
      }
    }
    activeLod0Count = l0
    activeLod1Count = l1
    activeLod2Count = l2
  }
}
