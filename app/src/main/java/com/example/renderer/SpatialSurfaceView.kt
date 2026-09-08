package com.example.renderer

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.arcore.ArCoreSessionManager
import com.example.arcore.ArCoreTrackingData
import com.example.arcore.DepthOcclusionManager
import com.example.arcore.ExhibitMarker
import com.example.arcore.ExhibitSource
import com.example.arcore.ImageMarkerCatalog
import com.example.arcore.ImageTrackingState
import com.example.engine.DiagnosticsLogger
import com.example.engine.SensorsManager
import com.example.engine.TwoFingerRotateDetector
import com.example.model.DisplayMode
import com.example.parser.GltfAssetFactory
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * High-performance Android SurfaceView bridging Google Filament and Google ARCore.
 * Supports:
 * - 60+ FPS Choreographer-driven rendering with zero allocations in doFrame.
 * - ARCore 6DoF Camera synchronization & plane anchoring.
 * - Image Target Recognition & Automatic 3D Exhibit Spawning & Anchoring.
 * - Multi-Object Scene persistence while walking in 6DoF space.
 * - Real 16-bit Depth extraction & Depth Occlusion processing.
 * - Real-world 1:1 Metric Scale (1 unit = 1 physical meter).
 * - Dual-Viewport Asymmetric Off-Axis Stereoscopic MR Pipeline.
 * - Two-finger rotation, pan, pinch zoom gestures.
 * - Hardware PixelCopy frame snapshots.
 */
class SpatialSurfaceView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {

  companion object {
    private const val TAG = "SpatialSurfaceView"
  }

  val filamentEngine = FilamentEngineHolder(context)
  val arCoreSessionManager = ArCoreSessionManager(context)
  val depthOcclusionManager: DepthOcclusionManager
    get() = arCoreSessionManager.depthOcclusionManager

  enum class WorldPlacementMode {
    PRE_ANCHOR_PREVIEW,
    ANCHORED_WORLD
  }

  var placementMode: WorldPlacementMode = WorldPlacementMode.PRE_ANCHOR_PREVIEW

  var dualCameraGLSurfaceView: DualCameraGLSurfaceView? = null
    set(value) {
      field = value
      value?.arCoreSessionManager = arCoreSessionManager
      value?.depthOcclusionManager = arCoreSessionManager.depthOcclusionManager
      value?.displayMode = displayMode
      if (value != null && (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR)) {
        val tex = value.textureId
        if (tex != 0) {
          arCoreSessionManager.setCameraTextureName(tex)
          (context as? Activity)?.let { arCoreSessionManager.resumeSession(it) }
        }
      }
    }

  private var isSurfaceReady = false
  private var isRendering = false

  // Frame timing
  private var lastFrameTimestamp = 0L
  private var frameCount = 0
  private var fpsTimer = 0L

  // Active Display Mode
  var displayMode: DisplayMode = DisplayMode.OBJECT
    set(value) {
      field = value
      updateModeConfiguration()
    }

  // Active Placed Anchors mapped to ARCore Anchor instances
  val activeArAnchors = mutableListOf<Anchor>()

  // Set of already spawned image markers to prevent duplicate spawning
  private val spawnedMarkerIds = mutableSetOf<String>()

  // Gesture Detectors
  private val scaleGestureDetector: ScaleGestureDetector
  private val rotateGestureDetector: TwoFingerRotateDetector
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var touchStartX = 0f
  private var touchStartY = 0f
  private var lastMidX = 0f
  private var lastMidY = 0f
  private var activePointerCount = 0
  private var touchStartTime = 0L

  // Current selected model ID for manual plane tap-placement
  var currentSelectedModelId: String = "drone_v1"
  var currentSelectedModelTitle: String = "Autonomous Drone X-1"

  // User Configured Interpupillary Distance (IPD) in millimeters for Stereoscopic MR
  var userIpdMm: Float = 64.0f

  // Telemetry and Event callbacks
  var onTelemetryUpdate: ((fps: Float, drawCalls: Int, vertexCount: Int, trackingData: ArCoreTrackingData) -> Unit)? = null
  var onAnchorPlaced: ((Anchor, FloatArray, ExhibitSource, String, String) -> Unit)? = null
  var onExhibitMarkerRecognized: ((ExhibitMarker, FloatArray) -> Unit)? = null
  var onScreenToggled: (() -> Unit)? = null
  var onRollDegreesChanged: ((Float) -> Unit)? = null
  var onAssetLoaded: ((trackCount: Int, durationSec: Float, trackNames: List<String>) -> Unit)? = null
  var onAnimationTick: ((currentTimeSec: Float) -> Unit)? = null

  // Latest AR tracking data
  private var latestTrackingData = ArCoreTrackingData()

  // Preallocated per-frame scratch buffers for zero garbage collection overhead
  private val scratchProjMatrix = FloatArray(16)
  private val scratchViewMatrix = FloatArray(16)
  private val scratchHeadPoseMatrix = FloatArray(16)
  private val scratchAnchorPoses = mutableListOf<Pose>()
  private val scratchObjPos = FloatArray(3)
  private val scratchCamForward = floatArrayOf(0f, 0f, -1f)
  private var lastDepthTimeMs: Long = 0L
  private var lastLodTimeMs: Long = 0L

  // Retained head pose matrix for graceful tracking loss recovery (no black screen or sudden jump)
  private val lastValidHeadPoseMatrix = FloatArray(16).apply {
    android.opengl.Matrix.setIdentityM(this, 0)
  }
  private var hasStoredHeadPose: Boolean = false
  // Last valid pose to hold primary anchor in world space during tracking pause
  private var lastKnownPrimaryPose: Pose? = null

  // Gesture state: seamless finger interaction for Rotate, Move, and Scale
  private var consecutiveNullFrames: Int = 0

  private var sensorPitch = 0f
  private var sensorRoll = 0f
  private var sensorYaw = 0f

  private val sensorsManager = SensorsManager(context) { pitch, roll, yaw ->
    sensorPitch = pitch
    sensorRoll = roll
    sensorYaw = yaw
  }

  init {
    holder.addCallback(this)
    holder.setFormat(PixelFormat.TRANSLUCENT)
    setZOrderMediaOverlay(true)

    filamentEngine.initialize()
    filamentEngine.onAssetLoaded = { count, duration, names ->
      post {
        onAssetLoaded?.invoke(count, duration, names)
      }
    }

    arCoreSessionManager.onTrackingDataUpdated = { trackingData ->
      latestTrackingData = trackingData

      // Update Environmental HDR lighting in Filament
      filamentEngine.updateEnvironmentalHdrLighting(
        mainLightDir = trackingData.mainLightDirection,
        mainLightIntensityRgb = trackingData.mainLightIntensity,
        colorCorrection = trackingData.colorCorrectionRgb
      )
    }

    arCoreSessionManager.onImageTrackingStateChanged = { markerName, state, anchor, pose ->
      handleImageTrackingState(markerName, state, anchor, pose)
    }

    scaleGestureDetector = ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          val scaleFactor = detector.scaleFactor
          filamentEngine.modelScale = (filamentEngine.modelScale * scaleFactor).coerceIn(
            FilamentEngineHolder.MIN_MODEL_SCALE,
            FilamentEngineHolder.MAX_MODEL_SCALE
          )
          return true
        }
      }
    )

    rotateGestureDetector = TwoFingerRotateDetector { deltaDegrees ->
      if (displayMode == DisplayMode.OBJECT) {
        filamentEngine.orbitYaw -= deltaDegrees
      } else {
        // 2 FINGER TWIST -> Rotate around the model (Yaw)
        filamentEngine.modelRotationDegrees = (filamentEngine.modelRotationDegrees - deltaDegrees) % 360f
      }
    }
  }

  /**
   * Handles ARCore AugmentedImage tracking state changes with robust lifecycle handling:
   * TRACKING -> TRACKING_LOST -> RECOVERING -> STOPPED.
   * Retains 3D model in place during temporary loss and only releases when STOPPED.
   */
  private fun handleImageTrackingState(markerId: String, state: ImageTrackingState, anchor: Anchor?, pose: Pose) {
    val marker = ImageMarkerCatalog.findByMarkerId(markerId) ?: return

    when (state) {
      ImageTrackingState.TRACKING -> {
        val existing = filamentEngine.activeExhibits.firstOrNull { it.markerId == markerId }
        if (existing == null && anchor != null) {
          activeArAnchors.add(anchor)
          placementMode = WorldPlacementMode.ANCHORED_WORLD
          arCoreSessionManager.registerAnchor(anchor)
          val glbBuffer = GltfAssetFactory.getPresetGlbBuffer(marker.modelId)
          if (glbBuffer != null) {
            val exhibitId = "exhibit_marker_${markerId}"
            filamentEngine.spawnExhibit(
              exhibitId = exhibitId,
              modelId = marker.modelId,
              title = marker.title,
              buffer = glbBuffer,
              anchor = anchor,
              source = ExhibitSource.IMAGE_MARKER,
              markerId = markerId
            )
            val pos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
            onExhibitMarkerRecognized?.invoke(marker, pos)
            onAnchorPlaced?.invoke(anchor, pos, ExhibitSource.IMAGE_MARKER, marker.modelId, marker.title)
            DiagnosticsLogger.log(TAG, "Image Marker Tracked: '${marker.title}' -> Anchored at (${pos[0]}, ${pos[1]}, ${pos[2]})")
          }
        } else if (existing != null && anchor != null) {
          existing.anchor = anchor
        }
      }
      ImageTrackingState.TRACKING_LOST -> {
        // Retain 3D model in place at last known pose; do NOT delete immediately!
        DiagnosticsLogger.log(TAG, "Image Marker '$markerId' temporarily lost -> holding pose")
      }
      ImageTrackingState.RECOVERING -> {
        DiagnosticsLogger.log(TAG, "Image Marker '$markerId' tracking recovering")
      }
      ImageTrackingState.STOPPED -> {
        val existing = filamentEngine.activeExhibits.firstOrNull { it.markerId == markerId }
        if (existing != null) {
          filamentEngine.removeExhibit(existing.id)
          activeArAnchors.remove(existing.anchor)
          DiagnosticsLogger.log(TAG, "Image Marker '$markerId' timed out -> removed 3D Exhibit")
        }
      }
    }
  }

  fun resume(activity: Activity) {
    if (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) {
      sensorsManager.start()
      if (arCoreSessionManager.isArCorePackageInstalled()) {
        try {
          arCoreSessionManager.resumeSession(activity)
        } catch (e: Exception) {
          Log.w(TAG, "ARCore resume skipped: ${e.message}")
        }
      }
    }
    startRendering()
  }

  fun pause() {
    stopRendering()
    sensorsManager.stop()
    arCoreSessionManager.pauseSession()
  }

  fun destroy() {
    stopRendering()
    clearAnchors()
    arCoreSessionManager.destroySession()
    filamentEngine.destroy()
  }

  private fun startRendering() {
    if (!isRendering && isSurfaceReady) {
      isRendering = true
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  private fun stopRendering() {
    isRendering = false
    Choreographer.getInstance().removeFrameCallback(this)
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    try {
      isSurfaceReady = true
      filamentEngine.onSurfaceCreated(holder.surface)
      startRendering()
    } catch (e: Exception) {
      Log.e(TAG, "Error in surfaceCreated: ${e.message}", e)
    }
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    try {
      filamentEngine.onSurfaceResized(width, height)
      arCoreSessionManager.setDisplayGeometry(
        (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: 0,
        width,
        height
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error in surfaceChanged: ${e.message}", e)
    }
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    isSurfaceReady = false
    stopRendering()
    filamentEngine.onSurfaceDestroyed()
  }

  private fun updateModeConfiguration() {
    filamentEngine.setDisplayMode(displayMode)
    when (displayMode) {
      DisplayMode.OBJECT -> {
        sensorsManager.stop()
        arCoreSessionManager.pauseSession()
        dualCameraGLSurfaceView?.displayMode = DisplayMode.OBJECT
      }
      DisplayMode.AR, DisplayMode.MR -> {
        sensorsManager.start()
        dualCameraGLSurfaceView?.arCoreSessionManager = arCoreSessionManager
        dualCameraGLSurfaceView?.depthOcclusionManager = depthOcclusionManager
        dualCameraGLSurfaceView?.displayMode = displayMode
        val currentTex = dualCameraGLSurfaceView?.textureId ?: 0
        if (currentTex != 0) {
          arCoreSessionManager.setCameraTextureName(currentTex)
        }
        try {
          (context as? Activity)?.let { arCoreSessionManager.resumeSession(it) }
        } catch (t: Throwable) {
          Log.w("SpatialSurfaceView", "Notice resuming ARCore session: ${t.message}")
        }
        startRendering()
      }
    }
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (!isRendering || !isSurfaceReady) return

    try {
      // Calculate FPS & Telemetry once per frame
      frameCount++
      if (lastFrameTimestamp == 0L) lastFrameTimestamp = frameTimeNanos
      val elapsedMs = (frameTimeNanos - fpsTimer) / 1_000_000L
      if (elapsedMs >= 500) {
        val calculatedFps = (frameCount * 1000.0f) / elapsedMs
        frameCount = 0
        fpsTimer = frameTimeNanos
        onTelemetryUpdate?.invoke(
          calculatedFps,
          filamentEngine.drawCalls,
          filamentEngine.vertexCount,
          latestTrackingData
        )
      }

      val nowMs = frameTimeNanos / 1_000_000L

      when (displayMode) {
        DisplayMode.OBJECT -> {
          filamentEngine.updateObjectModeTransform()
          filamentEngine.updateOrbitCamera()
          filamentEngine.renderFrame(frameTimeNanos)
        }

        DisplayMode.AR -> {
          val syncState = arCoreSessionManager.currentSynchronizedState
          if (syncState != null && syncState.trackingState == TrackingState.TRACKING) {
            consecutiveNullFrames = 0
            // Derive camera projection and view matrix strictly from this synchronized state
            filamentEngine.setCameraFromArCore(syncState.projectionMatrix, syncState.viewMatrix)

            // Extract camera forward vector from current synchronized view matrix
            scratchCamForward[0] = syncState.cameraForward[0]
            scratchCamForward[1] = syncState.cameraForward[1]
            scratchCamForward[2] = syncState.cameraForward[2]

            // Synchronize Environmental HDR lighting with this exact frame
            filamentEngine.updateEnvironmentalHdrLighting(
              mainLightDir = syncState.mainLightDirection,
              mainLightIntensityRgb = syncState.mainLightIntensity,
              colorCorrection = syncState.colorCorrectionRgb
            )

            // Depth Occlusion strictly tied to synchronized frame timestamp
            if (syncState.isDepthValid) {
              val pose = syncState.primaryAnchorPose
              val activeDist = if (pose != null) {
                val camPos = syncState.cameraPosition
                val dx = pose.tx() - camPos[0]
                val dy = pose.ty() - camPos[1]
                val dz = pose.tz() - camPos[2]
                Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
              } else 1.2f
              dualCameraGLSurfaceView?.virtualDepthMeters = activeDist

              filamentEngine.updateGpuDepthOcclusion(
                textureId = syncState.depthTextureId,
                width = syncState.depthWidth,
                height = syncState.depthHeight,
                timestampNs = syncState.depthTimestampNs,
                minDepth = syncState.minDepthMeters,
                maxDepth = syncState.maxDepthMeters,
                avgDepth = syncState.averageDepthMeters,
                isReady = true,
                occlusionPercentage = syncState.occlusionPercentage,
                depthUvTransformMatrix = syncState.depthUvTransformMatrix,
                viewMatrix = syncState.viewMatrix
              )
            }

            // Time-based Dynamic LOD evaluation: every ~150ms with zero heap allocations
            if (nowMs - lastLodTimeMs >= 150L) {
              lastLodTimeMs = nowMs
              val camPos = syncState.cameraPosition
              for (exhibit in filamentEngine.activeExhibits) {
                val anchor = exhibit.anchor
                if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                  scratchObjPos[0] = anchor.pose.tx()
                  scratchObjPos[1] = anchor.pose.ty()
                  scratchObjPos[2] = anchor.pose.tz()
                  val radius = maxOf(exhibit.physicalWidthMeters, exhibit.physicalHeightMeters) / 2.0f
                  filamentEngine.lodManager.evaluateLod(
                    exhibitId = exhibit.id,
                    cameraPos = camPos,
                    objectPos = scratchObjPos,
                    boundingRadiusMeters = radius,
                    screenWidthPx = width,
                    screenHeightPx = height
                  )
                }
              }
            }

            // Synchronize all exhibit transforms with gesture offsets and rotations
            filamentEngine.updateAllExhibitAnchorTransforms()

            // If no multi-exhibits spawned yet, update the single selected asset
            val currentAsset = filamentEngine.currentAsset
            if (currentAsset != null && filamentEngine.activeExhibits.isEmpty()) {
              if (placementMode == WorldPlacementMode.ANCHORED_WORLD) {
                val reconciledPose = syncState.primaryAnchorPose
                if (reconciledPose != null) {
                  lastKnownPrimaryPose = reconciledPose
                  filamentEngine.updateAnchorPose(currentAsset, reconciledPose)
                } else {
                  // Controlled Recovery: Hold at last known pose during tracking pause to eliminate jumping
                  lastKnownPrimaryPose?.let { lastPose ->
                    filamentEngine.updateAnchorPose(currentAsset, lastPose)
                  }
                }
              } else {
                // Initial placement preview mode before user taps to anchor
                filamentEngine.updateUnanchoredPose(currentAsset, syncState.cameraPosition, scratchCamForward)
              }
            }
          } else {
            // Robust AR Camera with Device Orientation when AR tracking is uninitialized, paused, or lost
            consecutiveNullFrames++
            filamentEngine.updateArCamera(sensorPitch, sensorYaw, sensorRoll)
            val currentAsset = filamentEngine.currentAsset
            if (currentAsset != null && filamentEngine.activeExhibits.isEmpty()) {
              if (placementMode == WorldPlacementMode.PRE_ANCHOR_PREVIEW) {
                filamentEngine.updateUnanchoredPose(currentAsset, null, scratchCamForward)
              }
              // If already anchored, freeze in world space (never drag relative to camera!)
            }
          }
          filamentEngine.renderFrame(frameTimeNanos)
        }

        DisplayMode.MR -> {
          val syncState = arCoreSessionManager.currentSynchronizedState
          val hasValidTracking = syncState != null && syncState.trackingState == TrackingState.TRACKING
          if (hasValidTracking && syncState != null) {
            System.arraycopy(syncState.viewMatrix, 0, scratchViewMatrix, 0, 16)
            if (android.opengl.Matrix.invertM(scratchHeadPoseMatrix, 0, scratchViewMatrix, 0)) {
              System.arraycopy(scratchHeadPoseMatrix, 0, lastValidHeadPoseMatrix, 0, 16)
              hasStoredHeadPose = true
            }
            scratchCamForward[0] = syncState.cameraForward[0]
            scratchCamForward[1] = syncState.cameraForward[1]
            scratchCamForward[2] = syncState.cameraForward[2]

            // Synchronized depth in MR
            if (syncState.isDepthValid) {
              filamentEngine.updateGpuDepthOcclusion(
                textureId = syncState.depthTextureId,
                width = syncState.depthWidth,
                height = syncState.depthHeight,
                timestampNs = syncState.depthTimestampNs,
                minDepth = syncState.minDepthMeters,
                maxDepth = syncState.maxDepthMeters,
                avgDepth = syncState.averageDepthMeters,
                isReady = true,
                occlusionPercentage = syncState.occlusionPercentage,
                depthUvTransformMatrix = syncState.depthUvTransformMatrix,
                viewMatrix = scratchViewMatrix
              )
            }
          } else if (hasStoredHeadPose) {
            // Decouple camera stream from tracking: retain last valid head pose during tracking loss
            System.arraycopy(lastValidHeadPoseMatrix, 0, scratchHeadPoseMatrix, 0, 16)
          }

          // Synchronize all exhibit transforms with finger gestures (rotation, scale, position)
          filamentEngine.updateAllExhibitAnchorTransforms()

          // If no multi-exhibits spawned yet, update single selected asset
          val currentAsset = filamentEngine.currentAsset
          if (currentAsset != null && filamentEngine.activeExhibits.isEmpty()) {
            if (placementMode == WorldPlacementMode.ANCHORED_WORLD) {
              val reconciledPose = syncState?.primaryAnchorPose
              if (reconciledPose != null) {
                lastKnownPrimaryPose = reconciledPose
                filamentEngine.updateAnchorPose(currentAsset, reconciledPose)
              } else {
                lastKnownPrimaryPose?.let { lastPose ->
                  filamentEngine.updateAnchorPose(currentAsset, lastPose)
                }
              }
            } else {
              filamentEngine.updateUnanchoredPose(currentAsset, latestTrackingData.cameraPosition, scratchCamForward)
            }
          }

          // Use user-calibrated IPD (e.g. 52mm - 74mm range)
          val ipdMeters = (userIpdMm / 1000.0f).coerceIn(0.045f, 0.085f)
          filamentEngine.renderStereoFrame(
            frameTimeNanos,
            ipdMeters,
            if (hasValidTracking || hasStoredHeadPose) scratchHeadPoseMatrix else null
          )
        }
      }

      if (filamentEngine.isPlayingAnimation) {
        onAnimationTick?.invoke(filamentEngine.currentAnimationTimeSec)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in doFrame: ${e.message}", e)
    } finally {
      if (isRendering) {
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
  }

  fun seekAnimation(timeSec: Float) {
    filamentEngine.seekAnimationTo(timeSec)
  }

  fun selectAnimationTrack(trackIndex: Int) {
    filamentEngine.selectAnimationTrack(trackIndex)
  }

  fun resetAnimation() {
    filamentEngine.resetAnimationPose()
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    rotateGestureDetector.onTouchEvent(event)
    scaleGestureDetector.onTouchEvent(event)

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchX = event.x
        lastTouchY = event.y
        touchStartX = event.x
        touchStartY = event.y
        activePointerCount = 1
        touchStartTime = System.currentTimeMillis()
        return true
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        activePointerCount = event.pointerCount
        if (event.pointerCount == 2) {
          lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
          lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
        } else if (event.pointerCount >= 3) {
          lastMidX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
          lastMidY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
        }
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount == 1) {
          val dx = event.x - lastTouchX
          val dy = event.y - lastTouchY

          if (displayMode == DisplayMode.OBJECT) {
            // 1 FINGER DRAG -> Move / Reposition model (Object Mode)
            filamentEngine.modelOffsetX = (filamentEngine.modelOffsetX + dx * 0.0015f).coerceIn(-1.5f, 1.5f)
            filamentEngine.modelOffsetY = (filamentEngine.modelOffsetY - dy * 0.0015f).coerceIn(-1.5f, 1.5f)
          } else {
            // 1 FINGER DRAG -> In AR/MR:
            // When PRE_ANCHOR_PREVIEW: allows nudging preview
            // When ANCHORED_WORLD: Model is locked to world anchor! Drag rotates model yaw smoothly without changing anchor position!
            if (placementMode == WorldPlacementMode.PRE_ANCHOR_PREVIEW) {
              filamentEngine.modelOffsetX += dx * 0.0015f
              filamentEngine.modelOffsetY -= dy * 0.0015f
            } else {
              filamentEngine.modelRotationDegrees = (filamentEngine.modelRotationDegrees - dx * 0.35f) % 360f
            }
          }
          lastTouchX = event.x
          lastTouchY = event.y
        } else if (event.pointerCount == 2) {
          val midX = (event.getX(0) + event.getX(1)) * 0.5f
          val midY = (event.getY(0) + event.getY(1)) * 0.5f
          val dMidX = midX - lastMidX
          val dMidY = midY - lastMidY

          // 2 FINGER GESTURES:
          // Pinch -> Handled by scaleGestureDetector (Scale)
          // Twist -> Handled by rotateGestureDetector (Rotate Yaw)
          // Vertical / Drag -> Adjust pitch around model when not actively scaling or twisting
          if (!scaleGestureDetector.isInProgress && !rotateGestureDetector.isActivelyTwisting) {
            if (displayMode == DisplayMode.OBJECT) {
              filamentEngine.modelPitchDegrees = (filamentEngine.modelPitchDegrees + dMidY * 0.45f) % 360f
              filamentEngine.orbitPitch = (filamentEngine.orbitPitch - dMidY * 0.45f).coerceIn(-80f, 80f)
            } else {
              filamentEngine.modelPitchDegrees = (filamentEngine.modelPitchDegrees + dMidY * 0.45f) % 360f
            }
          }
          lastMidX = midX
          lastMidY = midY
        } else if (event.pointerCount >= 3) {
          val midX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
          val dMidX = midX - lastMidX
          // 3 FINGER DRAG -> Roll (Z-axis) rotation around the model
          if (abs(dMidX) > 0.5f) {
            filamentEngine.modelRollDegrees = (filamentEngine.modelRollDegrees + dMidX * 0.45f) % 360f
            onRollDegreesChanged?.invoke(filamentEngine.modelRollDegrees)
          }
          lastMidX = midX
        }
        return true
      }

      MotionEvent.ACTION_UP -> {
        val duration = System.currentTimeMillis() - touchStartTime
        val movedDist = abs(event.x - touchStartX) + abs(event.y - touchStartY)
        if (movedDist < 30) {
          handleTap(event.x, event.y)
        }
        activePointerCount = 0
        return true
      }

      MotionEvent.ACTION_POINTER_UP -> {
        val upIndex = event.actionIndex
        val remainCount = event.pointerCount - 1
        activePointerCount = remainCount
        // Prevent sudden jumps when transitioning from 2 fingers to 1 finger or 3 to 2
        if (remainCount == 1) {
          val remainIndex = if (upIndex == 0) 1 else 0
          if (remainIndex < event.pointerCount) {
            lastTouchX = event.getX(remainIndex)
            lastTouchY = event.getY(remainIndex)
            touchStartX = lastTouchX
            touchStartY = lastTouchY
            touchStartTime = System.currentTimeMillis()
          }
        } else if (remainCount == 2) {
          var sumX = 0f
          var sumY = 0f
          for (i in 0 until event.pointerCount) {
            if (i != upIndex) {
              sumX += event.getX(i)
              sumY += event.getY(i)
            }
          }
          lastMidX = sumX * 0.5f
          lastMidY = sumY * 0.5f
        }
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        activePointerCount = 0
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  /**
   * Tap Handling:
   * - In AR/MR before placement: Places the model and locks it permanently to the world via ARCore Anchor.
   * - Once placed or in Object mode: Toggles UI overlay visibility cleanly.
   */
  private fun handleTap(xPx: Float, yPx: Float) {
    if ((displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) &&
        placementMode == WorldPlacementMode.PRE_ANCHOR_PREVIEW &&
        filamentEngine.currentAsset != null) {
      handlePlaceModelAt(xPx, yPx)
    } else {
      onScreenToggled?.invoke()
    }
  }

  /**
   * Places the active model at the targeted plane or surface.
   * Creates an ARCore Anchor exactly ONCE and transitions state to ANCHORED_WORLD.
   * The model becomes permanently world-locked; camera moves around the world while the model stays fixed.
   */
  private fun handlePlaceModelAt(xPx: Float, yPx: Float) {
    if (displayMode != DisplayMode.AR && displayMode != DisplayMode.MR) return
    if (placementMode == WorldPlacementMode.ANCHORED_WORLD) return

    try {
      val mappedX = if (displayMode == DisplayMode.MR && width > 0) {
        val halfWidth = width / 2f
        if (xPx > halfWidth) (xPx - halfWidth) * 2f else xPx * 2f
      } else {
        xPx
      }
      val frame = arCoreSessionManager.latestFrame ?: return
      if (frame.camera.trackingState != TrackingState.TRACKING) {
        DiagnosticsLogger.log(TAG, "Placement deferred: Camera tracking not yet stable (${frame.camera.trackingState})")
        return
      }

      var hit = arCoreSessionManager.performComprehensiveHitTest(frame, mappedX, yPx, width, height)
      if (hit == null && width > 0 && height > 0) {
        hit = arCoreSessionManager.performComprehensiveHitTest(frame, width / 2f, height / 2f, width, height)
      }

      val anchor: Anchor? = if (hit != null) {
        if (hit.hitResult != null) {
          arCoreSessionManager.createAnchor(hit.hitResult)
        } else {
          arCoreSessionManager.createAnchor(hit.hitPose)
        }
      } else {
        val camPose = frame.camera.pose
        val fallbackPoint = camPose.transformPoint(floatArrayOf(0f, -0.3f, -1.2f))
        val fallbackPose = Pose(fallbackPoint, floatArrayOf(0f, 0f, 0f, 1f))
        arCoreSessionManager.createAnchor(fallbackPose)
      }

      if (anchor != null) {
        for (oldAnchor in activeArAnchors) {
          try { oldAnchor.detach() } catch (_: Exception) {}
        }
        activeArAnchors.clear()
        activeArAnchors.add(anchor)
        placementMode = WorldPlacementMode.ANCHORED_WORLD
        arCoreSessionManager.registerPrimaryAnchor(anchor)

        filamentEngine.clearAllExhibits()
        val currentAsset = filamentEngine.currentAsset
        if (currentAsset != null) {
          filamentEngine.modelOffsetX = 0f
          filamentEngine.modelOffsetY = 0f
          filamentEngine.modelOffsetZ = 0f
          filamentEngine.updateAnchorPose(currentAsset, anchor.pose)
        }

        val hx = anchor.pose.tx()
        val hy = anchor.pose.ty()
        val hz = anchor.pose.tz()
        val posArr = floatArrayOf(hx, hy, hz)
        val source = when {
          hit?.isInstantTentative == true -> ExhibitSource.INSTANT_PLACEMENT
          hit?.hitType?.name?.contains("DEPTH") == true -> ExhibitSource.DEPTH_HIT
          hit != null -> ExhibitSource.PLANE_TAP
          else -> ExhibitSource.INSTANT_PLACEMENT
        }
        onAnchorPlaced?.invoke(anchor, posArr, source, currentSelectedModelId, currentSelectedModelTitle)
        Log.i(TAG, "ARCore Anchor WORLD-LOCKED at: $hx, $hy, $hz")
        DiagnosticsLogger.log(TAG, "Placed & World-Locked Anchor at ($hx, $hy, $hz)")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error during AR plane anchor hit test: ${e.message}")
    }
  }

  fun captureSnapshot(onCaptured: (Bitmap) -> Unit, onError: (String) -> Unit) {
    if (!isSurfaceReady || width <= 0 || height <= 0) {
      onError("Surface not ready for snapshot")
      return
    }

    try {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      PixelCopy.request(
        this,
        bitmap,
        { copyResult ->
          if (copyResult == PixelCopy.SUCCESS) {
            onCaptured(bitmap)
          } else {
            onError("PixelCopy failed with status: $copyResult")
          }
        },
        Handler(Looper.getMainLooper())
      )
    } catch (e: Throwable) {
      Log.e(TAG, "Error capturing snapshot: ${e.message}", e)
      onError("Snapshot capture failed: ${e.message}")
    }
  }

  fun loadGlbBuffer(buffer: ByteBuffer, title: String) {
    filamentEngine.loadAsset(buffer, title)
  }

  fun clearAnchors() {
    placementMode = WorldPlacementMode.PRE_ANCHOR_PREVIEW
    arCoreSessionManager.clearRegisteredAnchors()
    for (anchor in activeArAnchors) {
      anchor.detach()
    }
    activeArAnchors.clear()
    spawnedMarkerIds.clear()
    filamentEngine.clearAllExhibits()
  }

  fun clearModelAndScene() {
    clearAnchors()
    filamentEngine.clearAll()
    filamentEngine.clearGpuDepthAndTrackingResources()
    depthOcclusionManager.clear()
    arCoreSessionManager.environmentalMeshManager.clear()
    arCoreSessionManager.cloudAnchorManager.clearAll()
    spawnedMarkerIds.clear()
    lastKnownPrimaryPose = null
    hasStoredHeadPose = false
    currentSelectedModelId = ""
    currentSelectedModelTitle = ""
    arCoreSessionManager.handleTrackingLostOrReset(resetSession = false)
    arCoreSessionManager.resetWalkingOrigin()
  }

  fun resetView() {
    filamentEngine.resetTransforms()
    filamentEngine.clearGpuDepthAndTrackingResources()
    depthOcclusionManager.clear()
    arCoreSessionManager.environmentalMeshManager.clear()
    clearAnchors()
    lastKnownPrimaryPose = null
    hasStoredHeadPose = false
    arCoreSessionManager.handleTrackingLostOrReset(resetSession = false)
    arCoreSessionManager.resetWalkingOrigin()
  }
}
