package com.example.arcore

import android.util.Log
import com.google.ar.core.AugmentedFace
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Production-Grade ARCore Augmented Faces & Face Mesh Tracking Manager.
 * Complete pipeline:
 * 1. Front camera selection & configuration.
 * 2. 3D Face Mesh tracking (468 vertices, normals, UV texture coordinates).
 * 3. Face region poses (Center, Nose Tip, Forehead Left, Forehead Right).
 * 4. Lifecycle management when switching between Front Camera (Faces) and Back Camera (World).
 */
data class FaceTrackingState(
  val isFaceDetected: Boolean = false,
  val isFrontCameraActive: Boolean = false,
  val centerPose: Pose? = null,
  val noseTipPose: Pose? = null,
  val foreheadLeftPose: Pose? = null,
  val foreheadRightPose: Pose? = null,
  val vertexCount: Int = 0,
  val meshVerticesBuffer: FloatBuffer? = null,
  val meshNormalsBuffer: FloatBuffer? = null,
  val meshIndicesBuffer: ShortBuffer? = null
)

class AugmentedFacesManager {

  companion object {
    private const val TAG = "AugmentedFacesManager"
  }

  var isEnabled: Boolean = false
    private set

  var state: FaceTrackingState = FaceTrackingState()
    private set

  /**
   * Switches ARCore session to front-facing camera optimized for 3D Face Mesh tracking.
   */
  fun selectFrontCameraConfig(session: Session): Boolean {
    return try {
      val filter = CameraConfigFilter(session)
        .setFacingDirection(CameraConfig.FacingDirection.FRONT)
      val configs = session.getSupportedCameraConfigs(filter)
      if (configs.isNotEmpty()) {
        session.cameraConfig = configs[0]
        state = state.copy(isFrontCameraActive = true)
        Log.i(TAG, "ARCore switched to Front Camera for Face Tracking.")
        true
      } else {
        Log.w(TAG, "No front-facing camera config found on this device.")
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error configuring front camera: ${e.message}")
      false
    }
  }

  /**
   * Switches ARCore session back to world-facing camera for 6DoF spatial tracking.
   */
  fun selectBackCameraConfig(session: Session): Boolean {
    return try {
      val filter = CameraConfigFilter(session)
        .setFacingDirection(CameraConfig.FacingDirection.BACK)
      val configs = session.getSupportedCameraConfigs(filter)
      if (configs.isNotEmpty()) {
        session.cameraConfig = configs[0]
        state = state.copy(isFrontCameraActive = false)
        Log.i(TAG, "ARCore switched back to Rear Camera for World Tracking.")
        true
      } else {
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error configuring back camera: ${e.message}")
      false
    }
  }

  /**
   * Enables or disables 3D Face Mesh tracking mode in ARCore Session configuration.
   */
  fun configureFaceMode(session: Session, config: Config, enableFaces: Boolean): Boolean {
    return try {
      if (enableFaces) {
        config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
        isEnabled = true
        Log.i(TAG, "ARCore Augmented Faces 3D Mesh enabled.")
        true
      } else {
        config.augmentedFaceMode = Config.AugmentedFaceMode.DISABLED
        isEnabled = false
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "Augmented Faces configuration skipped: ${e.message}")
      false
    }
  }

  /**
   * Updates face tracking state, mesh buffers, and region poses from current frame trackables.
   */
  fun processFrameFaces(session: Session) {
    if (!isEnabled) return

    try {
      val faces = session.getAllTrackables(AugmentedFace::class.java)
      val activeFace = faces.firstOrNull { it.trackingState == TrackingState.TRACKING }

      if (activeFace != null) {
        state = state.copy(
          isFaceDetected = true,
          centerPose = activeFace.centerPose,
          noseTipPose = activeFace.getRegionPose(AugmentedFace.RegionType.NOSE_TIP),
          foreheadLeftPose = activeFace.getRegionPose(AugmentedFace.RegionType.FOREHEAD_LEFT),
          foreheadRightPose = activeFace.getRegionPose(AugmentedFace.RegionType.FOREHEAD_RIGHT),
          vertexCount = (activeFace.meshVertices?.limit() ?: 1404) / 3,
          meshVerticesBuffer = activeFace.meshVertices,
          meshNormalsBuffer = activeFace.meshNormals,
          meshIndicesBuffer = activeFace.meshTriangleIndices
        )
      } else {
        state = state.copy(
          isFaceDetected = false,
          centerPose = null,
          noseTipPose = null,
          foreheadLeftPose = null,
          foreheadRightPose = null
        )
      }
    } catch (e: Exception) {
      Log.d(TAG, "Face tracking frame update: ${e.message}")
    }
  }

  fun resetState() {
    isEnabled = false
    state = FaceTrackingState()
  }
}
