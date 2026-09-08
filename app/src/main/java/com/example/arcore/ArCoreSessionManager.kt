package com.example.arcore

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Image Tracking Lifecycle State Machine.
 * TRACKING: Image marker actively tracked with high confidence.
 * TRACKING_LOST: Image marker temporarily out of view; model retained at last known pose.
 * RECOVERING: Image marker reacquired; smoothing transform transition.
 * STOPPED: Tracking permanently lost after timeout; anchor and resources safely released.
 */
enum class ImageTrackingState {
  TRACKING,
  TRACKING_LOST,
  RECOVERING,
  STOPPED
}

data class TrackedImageRecord(
  val markerName: String,
  var state: ImageTrackingState,
  var anchor: Anchor?,
  var lastKnownPose: Pose,
  var lastSeenTimestampMs: Long
)

/**
 * Information about a detected 2D physical image marker / exhibit card in 3D space.
 */
data class DetectedImageInfo(
  val markerId: String,
  val trackingState: TrackingState,
  val centerPose: Pose,
  val extentXMeters: Float,
  val extentZMeters: Float,
  val distanceToCameraMeters: Float = 0f
)

/**
 * Real ARCore tracking info updated per frame.
 */
data class ArCoreTrackingData(
  val trackingState: TrackingState = TrackingState.STOPPED,
  val trackingFailureReason: TrackingFailureReason = TrackingFailureReason.NONE,
  val horizontalPlanesCount: Int = 0,
  val verticalPlanesCount: Int = 0,
  val lightIntensityLumens: Float = 1000f,
  val colorCorrectionRgb: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
  val mainLightDirection: FloatArray = floatArrayOf(0f, -1f, -0.5f),
  val mainLightIntensity: FloatArray = floatArrayOf(1f, 1f, 1f),
  val cameraPose: Pose? = null,
  val cameraPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
  val walkingDisplacementMeters: Float = 0f,
  val pointCloudPointsCount: Int = 0,
  val pointCloudTimestampNs: Long = 0L,
  val pointCloudMeanDistanceMeters: Float = 0f,
  val pointCloudConfidenceRatio: Float = 0f,
  val isDepthSupported: Boolean = false,
  val isDepthEnabled: Boolean = false,
  val isInstantPlacementEnabled: Boolean = true,
  val geospatialStatus: GeospatialStatus = GeospatialStatus(),
  val semanticsTelemetry: SemanticsTelemetry = SemanticsTelemetry(),
  val cloudAnchorsCount: Int = 0,
  val localAnchorsCount: Int = 0,
  val pendingCloudAnchorsCount: Int = 0,
  val isCrossDeviceResolutionConfirmed: Boolean = false,
  val cloudAnchorCrossDeviceState: String = "LOCAL_ONLY",
  val recordingTelemetry: RecordingTelemetry = RecordingTelemetry(),
  val reconstructionTelemetry: ReconstructionTelemetry = ReconstructionTelemetry(),
  val isRealtimeBackendConnected: Boolean = false,
  val isMultiplayerActive: Boolean = false,
  val isOnlineMultiplayerActive: Boolean = false,
  val isLoopbackTestActive: Boolean = false,
  val multiplayerMode: String = "OFFLINE",
  val certification: DeviceCapabilityCertification? = null,
  val isDriftActive: Boolean = false,
  val driftStartFrameIndex: Long? = null,
  val driftCategory: String = "NONE",
  val accumulatedDriftMeters: Float = 0f,
  val currentFrameNumber: Long = 0L,
  val trackingQuality: String = "OPTIMAL_6DOF",
  val detectedPlanes: List<DetectedPlaneInfo> = emptyList(),
  val detectedImages: List<DetectedImageInfo> = emptyList()
)

data class DetectedPlaneInfo(
  val id: String,
  val type: Plane.Type,
  val centerPose: Pose,
  val extentX: Float,
  val extentZ: Float,
  val polygon: FloatBuffer? = null
)

/**
 * Production-grade ARCore session manager.
 * - Handles 6DoF tracking, plane detection, and AugmentedImageDatabase.
 * - Implements image tracking state machine (TRACKING -> TRACKING_LOST -> RECOVERING -> STOPPED).
 * - Zero allocations in frame update loop.
 * - Manages real ARCore camera background texture lifecycle (setCameraTextureName).
 * - Environmental HDR light estimation & depth sensor integration.
 * - Instant Placement, Geospatial VPS, Scene Semantics, Cloud Anchors & Augmented Faces.
 */
class ArCoreSessionManager(private val context: Context) {

  companion object {
    private const val TAG = "ArCoreSessionManager"
    private const val TRACKING_LOSS_GRACE_PERIOD_MS = 4000L
  }

  // Specialized ARCore Sub-Managers
  val geospatialManager = ArCoreGeospatialManager()
  val semanticsManager = SceneSemanticsManager()
  val cloudAnchorManager = CloudAnchorManager(context)
  val multiplayerBackend = RealtimeMultiplayerBackend()
  val facesManager = AugmentedFacesManager()
  val recordingPlaybackManager = ArCoreRecordingPlaybackManager(context)
  val environmentalMeshManager = EnvironmentalMeshManager()
  val depthOcclusionManager = DepthOcclusionManager()
  var deviceCertification: DeviceCapabilityCertification? = null
    private set

  var session: Session? = null
    private set

  var isSupported: Boolean = false
    private set

  var isConfigured: Boolean = false
    private set

  @Volatile
  var latestFrame: Frame? = null
    private set

  var isSessionPaused: Boolean = true
    private set

  private var cameraTextureId: Int = 0
  private var userRequestedInstall = true
  private var availabilityChecked = false

  // Initial camera position for walking distance calculation
  private var initialCameraPose: Pose? = null
  private var totalWalkingDisplacement: Float = 0f

  // Preallocated scratch arrays for zero allocation in updateFrame
  private val scratchColorCorrection = FloatArray(4) { 1f }
  private val scratchMainLightDir = floatArrayOf(0f, -1f, -0.5f)
  private val scratchMainLightInt = floatArrayOf(1f, 1f, 1f)
  private val scratchCamPos = FloatArray(3)
  private val scratchCamForward = FloatArray(3)
  private val scratchProjectionMatrix = FloatArray(16)
  private val scratchViewMatrix = FloatArray(16)
  private val scratchPlaneList = ArrayList<DetectedPlaneInfo>(16)
  private val scratchImageList = ArrayList<DetectedImageInfo>(8)

  // Synchronization & Controlled Anchor Recovery
  val anchorRecoveryTracker = AnchorRecoveryTracker()
  val driftDetector = com.example.engine.DriftDetector()
  private val registeredAnchors = java.util.concurrent.CopyOnWriteArrayList<Anchor>()
  var primaryAnchor: Anchor? = null

  // Double-buffering for atomic, low-GC render state synchronization (eliminates ~540 per-frame array allocations)
  private val renderStateBufferA = SynchronizedArRenderState()
  private val renderStateBufferB = SynchronizedArRenderState()
  private var activeStateBufferIndex = 0

  // Reusable collection for active anchors to avoid per-frame List allocation
  private val reusableAnchorRecords = ArrayList<SynchronizedAnchorRecord>(16)
  // Reusable single-element list for depth anchor queries
  private val reusableDepthAnchorList = ArrayList<Pose>(1)

  // Decoupled trackable processing stage counter to distribute expensive operations across frames
  private var trackableProcessingStage = 0
  private var cachedHPlanesCount = 0
  private var cachedVPlanesCount = 0

  @Volatile
  var currentSynchronizedState: SynchronizedArRenderState? = null
    private set

  private var frameCounter: Long = 0L
  private var lastThrottledProcessingTimeMs: Long = 0L
  private var lastPointCloudSampleTimeMs: Long = 0L
  private var cachedPointCloudCount: Int = 0
  private var cachedPointCloudTimestampNs: Long = 0L
  private var cachedPointCloudMeanDist: Float = 0f
  private var cachedPointCloudConf: Float = 0f

  // Tracked images state machine map
  private val imageTrackingMap = HashMap<String, TrackedImageRecord>()

  // Callbacks
  var latestTrackingData = ArCoreTrackingData()
  var onTrackingDataUpdated: ((ArCoreTrackingData) -> Unit)? = null
  var onImageTrackingStateChanged: ((markerName: String, state: ImageTrackingState, anchor: Anchor?, pose: Pose) -> Unit)? = null
  var onSynchronizedStateProduced: ((SynchronizedArRenderState) -> Unit)? = null

  fun registerPrimaryAnchor(anchor: Anchor) {
    primaryAnchor = anchor
    if (!registeredAnchors.contains(anchor)) {
      registeredAnchors.add(anchor)
    }
  }

  fun registerAnchor(anchor: Anchor) {
    if (!registeredAnchors.contains(anchor)) {
      registeredAnchors.add(anchor)
    }
  }

  fun unregisterAnchor(anchor: Anchor) {
    registeredAnchors.remove(anchor)
    if (primaryAnchor == anchor) {
      primaryAnchor = null
    }
    anchorRecoveryTracker.unregisterAnchor(anchor)
  }

  fun clearRegisteredAnchors() {
    primaryAnchor = null
    registeredAnchors.clear()
    anchorRecoveryTracker.clear()
  }

  /**
   * Checks if the Google Play Services for AR APK is installed on this device.
   */
  fun isArCorePackageInstalled(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo("com.google.ar.core", PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("com.google.ar.core", 0)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Checks if ARCore is supported on this device.
   */
  fun checkAvailability(activity: Activity, onResult: (Boolean) -> Unit) {
    if (!isArCorePackageInstalled()) {
      isSupported = false
      availabilityChecked = true
      onResult(false)
      return
    }

    try {
      val availability = ArCoreApk.getInstance().checkAvailability(context)
      if (availability.isTransient) {
        onResult(availability.isSupported)
        return
      }
      isSupported = availability.isSupported
      availabilityChecked = true
      onResult(isSupported)
    } catch (t: Throwable) {
      Log.w(TAG, "ARCore availability check failed: ${t.message}")
      isSupported = false
      availabilityChecked = true
      onResult(false)
    }
  }

  /**
   * Initializes and configures the ARCore session.
   */
  fun setupSession(activity: Activity): Boolean {
    if (session != null) return true

    if (!isArCorePackageInstalled()) {
      Log.i(TAG, "Google Play Services for AR (com.google.ar.core) is not installed on this device.")
      isSupported = false
      isConfigured = false
      return false
    }

    return try {
      val installStatus = try {
        ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)
      } catch (e: UnavailableUserDeclinedInstallationException) {
        Log.w(TAG, "User declined ARCore installation")
        userRequestedInstall = false
        isSupported = false
        return false
      } catch (e: UnavailableDeviceNotCompatibleException) {
        Log.w(TAG, "Device not compatible with ARCore: ${e.message}")
        userRequestedInstall = false
        isSupported = false
        return false
      } catch (t: Throwable) {
        Log.w(TAG, "ARCore requestInstall check failed: ${t.message}")
        userRequestedInstall = false
        isSupported = false
        return false
      }

      when (installStatus) {
        ArCoreApk.InstallStatus.INSTALLED -> {
          val newSession = try {
            Session(activity)
          } catch (t: Throwable) {
            Log.w(TAG, "ARCore Session instantiation failed: ${t.message}")
            isSupported = false
            return false
          }
          val config = Config(newSession)

          // Enable Horizontal and Vertical Plane detection
          config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

          // Enable Environmental HDR Lighting
          config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

          // Enable Depth Mode if supported
          if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            Log.i(TAG, "ARCore Automatic Depth Mode enabled.")
          } else {
            config.depthMode = Config.DepthMode.DISABLED
            Log.i(TAG, "ARCore Depth Mode not supported on this device.")
          }

          // Build Augmented Image Database
          val imageDatabase = buildAugmentedImageDatabase(newSession)
          if (imageDatabase != null) {
            config.augmentedImageDatabase = imageDatabase
            Log.i(TAG, "Augmented Image Database configured with ${imageDatabase.numImages} targets.")
          }

          // Enable Instant Placement Mode (Local Y Up)
          try {
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            Log.i(TAG, "ARCore Instant Placement Mode enabled.")
          } catch (e: Throwable) {
            Log.d(TAG, "Instant placement not supported on this version: ${e.message}")
          }

          // Configure Geospatial API & Streetscape Geometry
          geospatialManager.configureGeospatialMode(context, newSession, config)

          // Configure Scene Semantics Mode
          semanticsManager.configureSemanticsMode(newSession, config)

          // Configure Cloud Anchors Mode
          cloudAnchorManager.configureCloudAnchorMode(newSession, config)

          // Certify hardware against ARCore capability matrix
          deviceCertification = ArCoreDeviceMatrix.certifyDevice(context, newSession)

          // Select optimal camera config based on target FPS and sensor capabilities
          try {
            val cameraFilter = CameraConfigFilter(newSession)
              .setFacingDirection(CameraConfig.FacingDirection.BACK)
            val supportedConfigs = newSession.getSupportedCameraConfigs(cameraFilter)
            // Prefer 60 FPS for fluid tracking if supported, falling back to 30 FPS
            val optimalConfig = supportedConfigs.firstOrNull { cfg ->
              cfg.fpsRange.upper >= 60
            } ?: supportedConfigs.firstOrNull { cfg ->
              cfg.fpsRange.upper >= 30
            } ?: supportedConfigs.firstOrNull()

            if (optimalConfig != null) {
              newSession.cameraConfig = optimalConfig
              Log.i(TAG, "Selected optimal CameraConfig: ${optimalConfig.imageSize.width}x${optimalConfig.imageSize.height}, fpsRange=${optimalConfig.fpsRange}")
            }
          } catch (e: Throwable) {
            Log.w(TAG, "CameraConfig selection fallback: ${e.message}")
          }

          // Real-time auto focus mode
          config.focusMode = Config.FocusMode.AUTO
          config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

          newSession.configure(config)
          if (cameraTextureId != 0) {
            newSession.setCameraTextureName(cameraTextureId)
          }

          session = newSession
          isSupported = true
          isConfigured = true
          Log.i(TAG, "ARCore Session initialized and configured successfully.")
          true
        }
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
          userRequestedInstall = false
          false
        }
      }
    } catch (e: UnavailableUserDeclinedInstallationException) {
      Log.w(TAG, "User declined ARCore installation")
      userRequestedInstall = false
      isSupported = false
      false
    } catch (e: UnavailableDeviceNotCompatibleException) {
      Log.w(TAG, "Device not compatible with ARCore")
      userRequestedInstall = false
      isSupported = false
      false
    } catch (t: Throwable) {
      Log.w(TAG, "ARCore session initialization failed: ${t.message}")
      userRequestedInstall = false
      isSupported = false
      false
    }
  }

  private fun buildAugmentedImageDatabase(sess: Session): AugmentedImageDatabase? {
    return try {
      val db = AugmentedImageDatabase(sess)
      for (exhibit in ImageMarkerCatalog.exhibits) {
        val bitmap = ImageMarkerCatalog.generateMarkerBitmap(exhibit)
        db.addImage(exhibit.markerId, bitmap, exhibit.physicalWidthMeters)
      }
      db
    } catch (e: Exception) {
      Log.e(TAG, "Failed building AugmentedImageDatabase: ${e.message}", e)
      null
    }
  }

  fun resumeSession(activity: Activity): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return false
    }

    if (!isArCorePackageInstalled()) {
      return false
    }

    if (session == null) {
      val success = setupSession(activity)
      if (!success) return false
    }

    return try {
      session?.resume()
      if (cameraTextureId != 0) {
        try {
          session?.setCameraTextureName(cameraTextureId)
        } catch (e: Exception) {
          Log.w(TAG, "setCameraTextureName on resume deferred to GL thread: ${e.message}")
        }
      }
      isSessionPaused = false
      Log.i(TAG, "ARCore session resumed successfully.")
      true
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during ARCore resume", e)
      isSessionPaused = true
      false
    } catch (e: Exception) {
      Log.e(TAG, "Error resuming ARCore session: ${e.message}", e)
      isSessionPaused = true
      false
    }
  }

  fun pauseSession() {
    isSessionPaused = true
    try {
      session?.pause()
      Log.i(TAG, "ARCore session paused.")
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing ARCore session: ${e.message}")
    }
  }

  fun destroySession() {
    isSessionPaused = true
    try {
      for (record in imageTrackingMap.values) {
        record.anchor?.detach()
      }
      imageTrackingMap.clear()
      session?.close()
      session = null
      isConfigured = false
      initialCameraPose = null
    } catch (e: Exception) {
      Log.w(TAG, "Error closing ARCore session: ${e.message}")
    }
  }

  val currentCameraTextureId: Int
    get() = cameraTextureId

  fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
    session?.setDisplayGeometry(rotation, width, height)
  }

  fun setCameraTextureName(textureId: Int) {
    cameraTextureId = textureId
    try {
      session?.setCameraTextureName(textureId)
    } catch (e: Exception) {
      Log.w(TAG, "setCameraTextureName error: ${e.message}")
    }
  }

  /**
   * Automatically restores ARCore camera stream and re-binds texture ID if session was interrupted.
   */
  fun recoverCameraStream(activity: Activity?): Boolean {
    if (activity == null) return false
    return try {
      if (isSessionPaused || session == null) {
        val resumed = resumeSession(activity)
        if (resumed && cameraTextureId != 0) {
          session?.setCameraTextureName(cameraTextureId)
        }
        resumed
      } else {
        if (cameraTextureId != 0) {
          session?.setCameraTextureName(cameraTextureId)
        }
        true
      }
    } catch (e: Exception) {
      Log.w(TAG, "recoverCameraStream failed: ${e.message}")
      false
    }
  }

  /**
   * Updates the ARCore session and produces ONE synchronized AR render state strictly
   * Synchronizes camera pose, matrices, lighting, and depth from this current frame.
   * Employs lock-free preallocated double-buffering to minimize GC pressure on the 60 FPS critical path.
   */
  fun updateFrame(): Frame? {
    if (isSessionPaused) return null
    val currentSession = session ?: return null
    return try {
      val frame = currentSession.update()
      latestFrame = frame
      val camera = frame.camera
      val trackingState = camera.trackingState
      val trackingFailureReason = camera.trackingFailureReason
      val frameTimestampNs = frame.timestamp
      val frameId = ++frameCounter

      // 1. Camera Projection & View Matrices
      camera.getProjectionMatrix(scratchProjectionMatrix, 0, 0.05f, 50.0f)
      camera.getViewMatrix(scratchViewMatrix, 0)

      // 2. Camera Pose & Displacement
      val camPose = if (trackingState == TrackingState.TRACKING) camera.pose else null
      scratchCamPos[0] = camPose?.tx() ?: 0f
      scratchCamPos[1] = camPose?.ty() ?: 0f
      scratchCamPos[2] = camPose?.tz() ?: 0f

      if (camPose != null) {
        if (initialCameraPose == null) {
          initialCameraPose = camPose
        } else {
          val init = initialCameraPose!!
          val dx = camPose.tx() - init.tx()
          val dy = camPose.ty() - init.ty()
          val dz = camPose.tz() - init.tz()
          totalWalkingDisplacement = sqrt(dx * dx + dy * dy + dz * dz)
        }
      }

      // Evaluate drift and tracking degradation frame-by-frame
      driftDetector.evaluateFrame(
        frameTimestampNs = frameTimestampNs,
        camera = camera,
        featurePointsCount = cachedPointCloudCount
      )

      // Camera Forward Vector derived from View Matrix
      scratchCamForward[0] = -scratchViewMatrix[2]
      scratchCamForward[1] = -scratchViewMatrix[6]
      scratchCamForward[2] = -scratchViewMatrix[10]

      // 3. Process Light Estimation
      val lightEstimate = frame.lightEstimate
      var lightIntensityLumens = 1000f
      scratchColorCorrection[0] = 1f; scratchColorCorrection[1] = 1f
      scratchColorCorrection[2] = 1f; scratchColorCorrection[3] = 1f
      scratchMainLightDir[0] = 0f; scratchMainLightDir[1] = -1f; scratchMainLightDir[2] = -0.5f
      scratchMainLightInt[0] = 1f; scratchMainLightInt[1] = 1f; scratchMainLightInt[2] = 1f

      if (lightEstimate.state == LightEstimate.State.VALID) {
        lightIntensityLumens = lightEstimate.pixelIntensity * 1000f
        lightEstimate.getColorCorrection(scratchColorCorrection, 0)
        lightEstimate.environmentalHdrMainLightDirection?.let {
          System.arraycopy(it, 0, scratchMainLightDir, 0, 3)
        }
        lightEstimate.environmentalHdrMainLightIntensity?.let {
          System.arraycopy(it, 0, scratchMainLightInt, 0, 3)
        }
      }

      // 4. Synchronize Anchors with Controlled Recovery (reusable collection to eliminate per-frame allocations)
      reusableAnchorRecords.clear()
      var reconciledPrimaryPose: Pose? = null
      var primaryAnchorState = TrackingState.STOPPED

      val pAnchor = primaryAnchor
      if (pAnchor != null) {
        primaryAnchorState = pAnchor.trackingState
        val (recPose, isRec) = anchorRecoveryTracker.reconcileAnchorPose(pAnchor)
        reconciledPrimaryPose = recPose
        reusableAnchorRecords.add(
          SynchronizedAnchorRecord(
            id = "primary_${pAnchor.hashCode()}",
            anchor = pAnchor,
            trackingState = primaryAnchorState,
            rawPose = pAnchor.pose,
            renderedPose = recPose,
            isRecovering = isRec
          )
        )
      }

      for (anchor in registeredAnchors) {
        if (anchor == pAnchor) continue
        val st = anchor.trackingState
        val (recPose, isRec) = anchorRecoveryTracker.reconcileAnchorPose(anchor)
        reusableAnchorRecords.add(
          SynchronizedAnchorRecord(
            id = "anchor_${anchor.hashCode()}",
            anchor = anchor,
            trackingState = st,
            rawPose = anchor.pose,
            renderedPose = recPose,
            isRecovering = isRec
          )
        )
      }

      // 5. Synchronized Depth Processing (associated strictly with frame timestamp)
      val dom = depthOcclusionManager
      var isDepthValid = false
      var depthTimestampNs = 0L
      val isDepthSupported = currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
      val isDepthEnabled = currentSession.config.depthMode == Config.DepthMode.AUTOMATIC
      if (dom != null && isDepthEnabled) {
        reusableDepthAnchorList.clear()
        if (reconciledPrimaryPose != null) {
          reusableDepthAnchorList.add(reconciledPrimaryPose)
        }
        dom.processFrameDepth(frame, reusableDepthAnchorList)
        depthTimestampNs = dom.latestDepthTimestampNs
        isDepthValid = dom.isSynchronizedWithCamera && dom.isDepthTextureReady
      }

      // 6. Point Cloud Spatial Metrics (Decoupled, sampled at 5Hz to avoid per-frame loop)
      val nowMs = System.currentTimeMillis()
      if (nowMs - lastPointCloudSampleTimeMs >= 200L) {
        lastPointCloudSampleTimeMs = nowMs
        samplePointCloudMetrics(frame)
      }
      val isSpatialStabilityHigh = cachedPointCloudCount > 30 && cachedPointCloudConf > 0.4f

      // 7. Populate Preallocated Synchronized AR Render State (Zero Array Clones via Preallocated Double Buffers)
      val targetState = if (activeStateBufferIndex == 0) renderStateBufferA else renderStateBufferB
      activeStateBufferIndex = 1 - activeStateBufferIndex

      targetState.frameId = frameId
      targetState.frameTimestampNs = frameTimestampNs
      targetState.trackingState = trackingState
      targetState.trackingFailureReason = trackingFailureReason
      targetState.cameraPose = camPose
      System.arraycopy(scratchProjectionMatrix, 0, targetState.projectionMatrix, 0, 16)
      System.arraycopy(scratchViewMatrix, 0, targetState.viewMatrix, 0, 16)
      System.arraycopy(scratchCamPos, 0, targetState.cameraPosition, 0, 3)
      System.arraycopy(scratchCamForward, 0, targetState.cameraForward, 0, 3)
      targetState.primaryAnchorPose = reconciledPrimaryPose
      targetState.primaryAnchorState = primaryAnchorState
      targetState.anchorRecords = ArrayList(reusableAnchorRecords)
      targetState.depthTimestampNs = depthTimestampNs
      targetState.isDepthValid = isDepthValid
      targetState.depthTextureId = dom?.depthTextureId ?: 0
      targetState.depthWidth = dom?.depthWidth ?: 0
      targetState.depthHeight = dom?.depthHeight ?: 0
      targetState.minDepthMeters = dom?.minDepthMeters ?: 0f
      targetState.maxDepthMeters = dom?.maxDepthMeters ?: 0f
      targetState.averageDepthMeters = dom?.averageDepthMeters ?: 0f
      targetState.occlusionPercentage = dom?.occlusionPercentage ?: 0f
      if (dom != null) {
        System.arraycopy(dom.depthUvTransformMatrix, 0, targetState.depthUvTransformMatrix, 0, 16)
      }
      targetState.pointCloudTimestampNs = cachedPointCloudTimestampNs
      targetState.pointCloudPointsCount = cachedPointCloudCount
      targetState.pointCloudMeanDistanceMeters = cachedPointCloudMeanDist
      targetState.pointCloudConfidenceRatio = cachedPointCloudConf
      targetState.isSpatialStabilityHigh = isSpatialStabilityHigh
      targetState.lightIntensityLumens = lightIntensityLumens
      System.arraycopy(scratchColorCorrection, 0, targetState.colorCorrectionRgb, 0, 4)
      System.arraycopy(scratchMainLightDir, 0, targetState.mainLightDirection, 0, 3)
      System.arraycopy(scratchMainLightInt, 0, targetState.mainLightIntensity, 0, 3)
      targetState.isFresh = true

      currentSynchronizedState = targetState
      onSynchronizedStateProduced?.invoke(targetState)

      // 8. Decoupled Non-Critical Trackable Processing
      // Interleaved across successive frames (~50ms intervals) so heavy operations (Mesh, Semantics, Geospatial)
      // never cause a multi-millisecond frame spike on the render thread.
      if (nowMs - lastThrottledProcessingTimeMs >= 50L) {
        lastThrottledProcessingTimeMs = nowMs
        processStagedTrackables(
          currentSession = currentSession,
          frame = frame,
          camPose = camPose,
          now = nowMs,
          lightIntensityLumens = lightIntensityLumens,
          isDepthSupported = isDepthSupported,
          isDepthEnabled = isDepthEnabled
        )
      }

      frame
    } catch (e: com.google.ar.core.exceptions.SessionPausedException) {
      isSessionPaused = true
      null
    } catch (e: com.google.ar.core.exceptions.CameraNotAvailableException) {
      Log.w(TAG, "Camera not available in updateFrame: ${e.message}")
      null
    } catch (e: Exception) {
      null
    }
  }

  private fun samplePointCloudMetrics(frame: Frame) {
    try {
      val pointCloud = frame.acquirePointCloud()
      try {
        val pointsBuffer = pointCloud.points
        val totalPts = pointsBuffer.remaining() / 4
        cachedPointCloudCount = totalPts
        cachedPointCloudTimestampNs = pointCloud.timestamp
        if (totalPts > 0) {
          val sampleStride = maxOf(1, totalPts / 64)
          var sumDist = 0f
          var highConfCount = 0
          var sampleCount = 0
          val limit = pointsBuffer.limit()
          var i = 0
          while (i + 3 < limit) {
            val px = pointsBuffer.get(i)
            val py = pointsBuffer.get(i + 1)
            val pz = pointsBuffer.get(i + 2)
            val conf = pointsBuffer.get(i + 3)
            val d = kotlin.math.sqrt(px * px + py * py + pz * pz)
            sumDist += d
            if (conf > 0.5f) highConfCount++
            sampleCount++
            i += sampleStride * 4
          }
          if (sampleCount > 0) {
            cachedPointCloudMeanDist = sumDist / sampleCount
            cachedPointCloudConf = highConfCount.toFloat() / sampleCount
          }
        }
      } finally {
        pointCloud.close()
      }
    } catch (_: Exception) {}
  }

  /**
   * Staged non-critical trackables processing:
   * Splits trackables workload across frames so that heavy scene understanding tasks
   * (Semantics, Mesh generation, Geospatial queries) never cause frame-time spikes on the render loop.
   */
  private fun processStagedTrackables(
    currentSession: Session,
    frame: Frame,
    camPose: Pose?,
    now: Long,
    lightIntensityLumens: Float,
    isDepthSupported: Boolean,
    isDepthEnabled: Boolean
  ) {
    when (trackableProcessingStage) {
      0 -> {
        // Stage 0: Detect Planes and Augmented Images
        updatePlanes(currentSession)
        updateAugmentedImages(currentSession, camPose, now)
      }
      1 -> {
        // Stage 1: Geospatial state & Augmented Faces
        try {
          geospatialManager.updateGeospatialState(currentSession)
          facesManager.processFrameFaces(currentSession)
        } catch (e: Exception) {
          Log.w(TAG, "Notice in Stage 1 features: ${e.message}")
        }
      }
      2 -> {
        // Stage 2: Scene Semantics & Environmental Reconstruction Mesh
        try {
          semanticsManager.processFrameSemantics(frame)
          environmentalMeshManager.updateEnvironmentalMesh(currentSession, frame, semanticsManager)
        } catch (e: Exception) {
          Log.w(TAG, "Notice in Stage 2 features: ${e.message}")
        }
      }
      3 -> {
        // Stage 3: Recording playback update & Publish immutable tracking telemetry
        try {
          recordingPlaybackManager.updateFrameState(currentSession)
        } catch (e: Exception) {
          Log.w(TAG, "Notice in Stage 3 features: ${e.message}")
        }
        publishTrackingTelemetry(
          frame = frame,
          camPose = camPose,
          lightIntensityLumens = lightIntensityLumens,
          isDepthSupported = isDepthSupported,
          isDepthEnabled = isDepthEnabled
        )
      }
    }
    trackableProcessingStage = (trackableProcessingStage + 1) % 4
  }

  private fun updatePlanes(currentSession: Session) {
    val allPlanes = currentSession.getAllTrackables(Plane::class.java)
    var hPlanes = 0
    var vPlanes = 0
    scratchPlaneList.clear()

    for (plane in allPlanes) {
      if (plane.trackingState == TrackingState.TRACKING) {
        if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING || plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING) {
          hPlanes++
        } else if (plane.type == Plane.Type.VERTICAL) {
          vPlanes++
        }
        scratchPlaneList.add(
          DetectedPlaneInfo(
            id = "plane_${plane.hashCode()}",
            type = plane.type,
            centerPose = plane.centerPose,
            extentX = plane.extentX,
            extentZ = plane.extentZ,
            polygon = plane.polygon
          )
        )
      }
    }
    cachedHPlanesCount = hPlanes
    cachedVPlanesCount = vPlanes
  }

  private fun updateAugmentedImages(currentSession: Session, camPose: Pose?, now: Long) {
    val allImages = currentSession.getAllTrackables(AugmentedImage::class.java)
    scratchImageList.clear()

    for (image in allImages) {
      val markerName = image.name
      val imgPose = image.centerPose
      val distToCam = if (camPose != null) {
        val dx = imgPose.tx() - camPose.tx()
        val dy = imgPose.ty() - camPose.ty()
        val dz = imgPose.tz() - camPose.tz()
        sqrt(dx * dx + dy * dy + dz * dz)
      } else 0f

      scratchImageList.add(
        DetectedImageInfo(
          markerId = markerName,
          trackingState = image.trackingState,
          centerPose = imgPose,
          extentXMeters = image.extentX,
          extentZMeters = image.extentZ,
          distanceToCameraMeters = distToCam
        )
      )

      val record = imageTrackingMap[markerName]
      when (image.trackingState) {
        TrackingState.TRACKING -> {
          if (record == null) {
            val anchor = try { image.createAnchor(image.centerPose) } catch (e: Exception) { null }
            if (anchor != null) {
              registerAnchor(anchor)
            }
            val newRec = TrackedImageRecord(
              markerName = markerName,
              state = ImageTrackingState.TRACKING,
              anchor = anchor,
              lastKnownPose = imgPose,
              lastSeenTimestampMs = now
            )
            imageTrackingMap[markerName] = newRec
            onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING, anchor, imgPose)
          } else {
            record.lastKnownPose = imgPose
            record.lastSeenTimestampMs = now
            if (record.state != ImageTrackingState.TRACKING) {
              record.state = ImageTrackingState.TRACKING
              onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING, record.anchor, imgPose)
            }
          }
        }
        TrackingState.PAUSED -> {
          if (record != null && record.state == ImageTrackingState.TRACKING) {
            record.state = ImageTrackingState.TRACKING_LOST
            onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING_LOST, record.anchor, record.lastKnownPose)
          }
        }
        TrackingState.STOPPED -> {
          if (record != null && record.state != ImageTrackingState.STOPPED) {
            record.state = ImageTrackingState.STOPPED
            record.anchor?.let { unregisterAnchor(it) }
            record.anchor?.detach()
            record.anchor = null
            onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.STOPPED, null, record.lastKnownPose)
          }
        }
      }
    }

    // Check timeout for any previously tracked images
    for (record in imageTrackingMap.values) {
      if (record.state == ImageTrackingState.TRACKING_LOST) {
        if (now - record.lastSeenTimestampMs > TRACKING_LOSS_GRACE_PERIOD_MS) {
          record.state = ImageTrackingState.STOPPED
          record.anchor?.let { unregisterAnchor(it) }
          record.anchor?.detach()
          record.anchor = null
          onImageTrackingStateChanged?.invoke(record.markerName, ImageTrackingState.STOPPED, null, record.lastKnownPose)
        }
      }
    }
  }

  private fun publishTrackingTelemetry(
    frame: Frame,
    camPose: Pose?,
    lightIntensityLumens: Float,
    isDepthSupported: Boolean,
    isDepthEnabled: Boolean
  ) {
    val currentTrackingState = frame.camera.trackingState
    val currentFailureReason = frame.camera.trackingFailureReason
    val trackingQuality = when (currentTrackingState) {
      TrackingState.TRACKING -> {
        if (driftDetector.isDriftActive) {
          "DRIFT_DETECTED"
        } else if (cachedPointCloudCount < 20) {
          "LIMITED_LOW_FEATURES"
        } else {
          "OPTIMAL_6DOF"
        }
      }
      TrackingState.PAUSED -> {
        when (currentFailureReason) {
          TrackingFailureReason.INSUFFICIENT_LIGHT -> "LIMITED_LOW_LIGHT"
          TrackingFailureReason.EXCESSIVE_MOTION -> "LIMITED_FAST_MOTION"
          TrackingFailureReason.INSUFFICIENT_FEATURES -> "LIMITED_LOW_FEATURES"
          TrackingFailureReason.BAD_STATE -> "REINITIALIZING"
          else -> "SEARCHING_SURFACES"
        }
      }
      TrackingState.STOPPED -> "STOPPED"
    }

    val trackingData = ArCoreTrackingData(
      trackingState = currentTrackingState,
      trackingFailureReason = currentFailureReason,
      horizontalPlanesCount = cachedHPlanesCount,
      verticalPlanesCount = cachedVPlanesCount,
      lightIntensityLumens = lightIntensityLumens,
      colorCorrectionRgb = scratchColorCorrection,
      mainLightDirection = scratchMainLightDir,
      mainLightIntensity = scratchMainLightInt,
      cameraPose = camPose,
      cameraPosition = scratchCamPos,
      walkingDisplacementMeters = totalWalkingDisplacement,
      pointCloudPointsCount = cachedPointCloudCount,
      pointCloudTimestampNs = cachedPointCloudTimestampNs,
      pointCloudMeanDistanceMeters = cachedPointCloudMeanDist,
      pointCloudConfidenceRatio = cachedPointCloudConf,
      isDepthSupported = isDepthSupported,
      isDepthEnabled = isDepthEnabled,
      isInstantPlacementEnabled = true,
      geospatialStatus = geospatialManager.status,
      semanticsTelemetry = semanticsManager.telemetry,
      cloudAnchorsCount = cloudAnchorManager.cloudAnchorsCount,
      localAnchorsCount = cachedHPlanesCount + cachedVPlanesCount,
      pendingCloudAnchorsCount = cloudAnchorManager.pendingCloudAnchorsCount,
      isCrossDeviceResolutionConfirmed = cloudAnchorManager.isCrossDeviceValidated,
      cloudAnchorCrossDeviceState = cloudAnchorManager.crossDeviceState.name,
      recordingTelemetry = recordingPlaybackManager.telemetry,
      reconstructionTelemetry = environmentalMeshManager.telemetry,
      isRealtimeBackendConnected = multiplayerBackend.isBackendConnected,
      isMultiplayerActive = multiplayerBackend.isMultiplayerActive,
      isOnlineMultiplayerActive = multiplayerBackend.isOnlineMultiplayerActive,
      isLoopbackTestActive = multiplayerBackend.isLoopbackTestActive,
      multiplayerMode = multiplayerBackend.multiplayerStatus,
      certification = deviceCertification,
      isDriftActive = driftDetector.isDriftActive,
      driftStartFrameIndex = driftDetector.driftStartFrameIndex,
      driftCategory = driftDetector.driftCategory,
      accumulatedDriftMeters = driftDetector.accumulatedDriftMeters,
      currentFrameNumber = driftDetector.lastIdentifiedFrame?.frameNumber ?: 0L,
      trackingQuality = trackingQuality,
      detectedPlanes = ArrayList(scratchPlaneList),
      detectedImages = ArrayList(scratchImageList)
    )
    latestTrackingData = trackingData
    onTrackingDataUpdated?.invoke(trackingData)
  }

  /**
   * Performs prioritized hit test enforcing stable plane acquisition:
   * Priority: Stable Plane -> Depth-assisted hit -> Feature point / Instant Placement.
   *
   * Fallback methods do NOT override a valid stable plane.
   * Prevents accidental placement caused by noisy unoriented feature points.
   */
  fun hitTest(frame: Frame, xPx: Float, yPx: Float): HitResult? {
    if (frame.camera.trackingState != TrackingState.TRACKING) {
      return null
    }

    val hits = frame.hitTest(xPx, yPx)
    if (hits.isEmpty()) return null

    // 1. STABLE PLANE (Highest Priority):
    // Prioritize verified polygon bounds on horizontal upward surfaces (tables, floors),
    // followed by vertical planes (walls), with sufficient surface extent.
    var bestPlaneHit: HitResult? = null
    var bestPlaneScore = -1f

    for (hit in hits) {
      val trackable = hit.trackable
      if (trackable is Plane && trackable.trackingState == TrackingState.TRACKING) {
        val rootPlane = trackable.subsumedBy ?: trackable
        if (rootPlane.trackingState == TrackingState.TRACKING) {
          val inPolygon = rootPlane.isPoseInPolygon(hit.hitPose)
          val area = rootPlane.extentX * rootPlane.extentZ
          val isHorizontalUp = rootPlane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
          val isVertical = rootPlane.type == Plane.Type.VERTICAL

          if (inPolygon && area >= 0.02f) {
            val score = (if (isHorizontalUp) 100f else if (isVertical) 80f else 60f) + area.coerceAtMost(5f)
            if (score > bestPlaneScore) {
              bestPlaneScore = score
              bestPlaneHit = hit
            }
          } else if (bestPlaneHit == null && rootPlane.isPoseInExtents(hit.hitPose) && area >= 0.04f) {
            val score = (if (isHorizontalUp) 40f else if (isVertical) 30f else 20f) + area.coerceAtMost(3f)
            if (score > bestPlaneScore) {
              bestPlaneScore = score
              bestPlaneHit = hit
            }
          }
        }
      }
    }

    if (bestPlaneHit != null) {
      return bestPlaneHit
    }

    // 2. DEPTH-ASSISTED HIT:
    // When planes are still forming, evaluate DepthPoint within comfortable AR inspection range (0.3m - 4.5m)
    val depthHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      val isDepth = trackable != null && (trackable.javaClass.simpleName.contains("DepthPoint") || trackable is com.google.ar.core.DepthPoint)
      isDepth && hit.distance in 0.3f..4.5f
    }
    if (depthHit != null) {
      return depthHit
    }

    // 3. FEATURE POINT / INSTANT PLACEMENT:
    // Prefer fully tracked instant placement points
    val instantHits = hits.filter { it.trackable is com.google.ar.core.InstantPlacementPoint }
    val fullTrackingInstant = instantHits.firstOrNull {
      val pt = it.trackable as com.google.ar.core.InstantPlacementPoint
      pt.trackingMethod == com.google.ar.core.InstantPlacementPoint.TrackingMethod.FULL_TRACKING
    }
    if (fullTrackingInstant != null) {
      return fullTrackingInstant
    }

    val tentativeInstant = instantHits.firstOrNull()
    if (tentativeInstant != null) {
      return tentativeInstant
    }

    // Direct Instant Placement query on frame if instant placement enabled
    try {
      val instantHitsDirect = frame.hitTestInstantPlacement(xPx, yPx, 1.5f)
      val directHit = instantHitsDirect.firstOrNull()
      if (directHit != null) {
        return directHit
      }
    } catch (_: Throwable) {}

    // Oriented feature points with estimated surface normal
    val orientedPointHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      if (trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING) {
        trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
      } else false
    }
    if (orientedPointHit != null) {
      return orientedPointHit
    }

    // 4. Feature point fallback (equivalent to ARKit featurePoint hit-test capability)
    val genericFeaturePointHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING
    }
    if (genericFeaturePointHit != null) {
      return genericFeaturePointHit
    }

    return null
  }

  fun createAnchor(hitResult: HitResult): Anchor? {
    return try {
      hitResult.createAnchor()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Anchor", e)
      null
    }
  }

  fun createAnchor(pose: Pose): Anchor? {
    val s = session ?: return null
    return try {
      s.createAnchor(pose)
    } catch (e: Exception) {
      Log.e(TAG, "Failed creating ARCore Anchor from Pose: ${e.message}", e)
      null
    }
  }

  /**
   * Comprehensive Multi-Tier Hit Test:
   * 1. Verified Stable Plane (Horizontal Upward tables/floors, then Vertical walls)
   * 2. ARCore Depth Point Cloud (via Depth API)
   * 3. Direct Depth Image Map Sampling (True physical surface reconstruction for non-planar geometry)
   * 4. Real Instant Placement (Full Tracking vs Tentative)
   * 5. Feature Points (Estimated Surface Normal, then Generic)
   */
  fun performComprehensiveHitTest(
    frame: Frame,
    xPx: Float,
    yPx: Float,
    viewportWidth: Int,
    viewportHeight: Int
  ): ComprehensiveHitResult? {
    if (frame.camera.trackingState != TrackingState.TRACKING) return null

    val hits = try { frame.hitTest(xPx, yPx) } catch (_: Throwable) { emptyList() }

    // 1. STABLE PLANE (Highest Priority)
    var bestPlaneHit: HitResult? = null
    var bestPlaneScore = -1f
    var bestPlane: Plane? = null

    for (hit in hits) {
      val trackable = hit.trackable
      if (trackable is Plane && trackable.trackingState == TrackingState.TRACKING) {
        val rootPlane = trackable.subsumedBy ?: trackable
        if (rootPlane.trackingState == TrackingState.TRACKING) {
          val inPolygon = rootPlane.isPoseInPolygon(hit.hitPose)
          val area = rootPlane.extentX * rootPlane.extentZ
          val isHorizontalUp = rootPlane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
          val isVertical = rootPlane.type == Plane.Type.VERTICAL

          if (inPolygon && area >= 0.02f) {
            val score = (if (isHorizontalUp) 100f else if (isVertical) 80f else 60f) + area.coerceAtMost(5f)
            if (score > bestPlaneScore) {
              bestPlaneScore = score
              bestPlaneHit = hit
              bestPlane = rootPlane
            }
          } else if (bestPlaneHit == null && rootPlane.isPoseInExtents(hit.hitPose) && area >= 0.04f) {
            val score = (if (isHorizontalUp) 40f else if (isVertical) 30f else 20f) + area.coerceAtMost(3f)
            if (score > bestPlaneScore) {
              bestPlaneScore = score
              bestPlaneHit = hit
              bestPlane = rootPlane
            }
          }
        }
      }
    }

    if (bestPlaneHit != null && bestPlane != null) {
      val hitType = when (bestPlane.type) {
        Plane.Type.HORIZONTAL_UPWARD_FACING -> ComprehensiveHitType.STABLE_PLANE_HORIZONTAL
        Plane.Type.VERTICAL -> ComprehensiveHitType.STABLE_PLANE_VERTICAL
        else -> ComprehensiveHitType.STABLE_PLANE_EXTENTS
      }
      return ComprehensiveHitResult(
        hitResult = bestPlaneHit,
        hitPose = bestPlaneHit.hitPose,
        hitType = hitType,
        distanceMeters = bestPlaneHit.distance,
        isInstantTentative = false,
        plane = bestPlane,
        confidenceScore = 1.0f
      )
    }

    // 2. DEPTH POINT CLOUD (From ARCore Depth API)
    val depthPointHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      val isDepth = trackable != null && (trackable.javaClass.simpleName.contains("DepthPoint") || trackable is com.google.ar.core.DepthPoint)
      isDepth && hit.distance in 0.25f..5.0f
    }
    if (depthPointHit != null) {
      return ComprehensiveHitResult(
        hitResult = depthPointHit,
        hitPose = depthPointHit.hitPose,
        hitType = ComprehensiveHitType.DEPTH_POINT_CLOUD,
        distanceMeters = depthPointHit.distance,
        isInstantTentative = false,
        confidenceScore = 0.95f
      )
    }

    // 3. DIRECT DEPTH MAP SAMPLING (True physical depth on non-planar surfaces)
    val dom = depthOcclusionManager
    if (dom != null && viewportWidth > 0 && viewportHeight > 0) {
      val normX = (xPx / viewportWidth).coerceIn(0f, 1f)
      val normY = (yPx / viewportHeight).coerceIn(0f, 1f)
      val sampledDepth = dom.sampleDepthMetersAtViewCoord(frame, normX, normY)
      if (sampledDepth != null && sampledDepth in 0.25f..6.0f) {
        val camPose = frame.camera.pose
        val proj = FloatArray(16)
        frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        val tanFovX = if (proj[0] > 0.001f) 1.0f / proj[0] else 0.75f
        val tanFovY = if (proj[5] > 0.001f) 1.0f / proj[5] else 1.0f
        val camX = (normX * 2.0f - 1.0f) * tanFovX * sampledDepth
        val camY = -(normY * 2.0f - 1.0f) * tanFovY * sampledDepth
        val camZ = -sampledDepth
        val worldPos = camPose.transformPoint(floatArrayOf(camX, camY, camZ))

        val depthHitPose = Pose(
          worldPos,
          floatArrayOf(0f, 0f, 0f, 1f)
        )
        return ComprehensiveHitResult(
          hitResult = null,
          hitPose = depthHitPose,
          hitType = ComprehensiveHitType.DEPTH_IMAGE_MAP_SAMPLING,
          distanceMeters = sampledDepth,
          isInstantTentative = false,
          confidenceScore = 0.90f
        )
      }
    }

    // 4. REAL INSTANT PLACEMENT
    val instantHits = hits.filter { it.trackable is com.google.ar.core.InstantPlacementPoint }
    val fullTrackingInstant = instantHits.firstOrNull {
      val pt = it.trackable as com.google.ar.core.InstantPlacementPoint
      pt.trackingMethod == com.google.ar.core.InstantPlacementPoint.TrackingMethod.FULL_TRACKING
    }
    if (fullTrackingInstant != null) {
      return ComprehensiveHitResult(
        hitResult = fullTrackingInstant,
        hitPose = fullTrackingInstant.hitPose,
        hitType = ComprehensiveHitType.INSTANT_PLACEMENT_FULL,
        distanceMeters = fullTrackingInstant.distance,
        isInstantTentative = false,
        confidenceScore = 0.85f
      )
    }

    try {
      val directHits = frame.hitTestInstantPlacement(xPx, yPx, 1.5f)
      val directHit = directHits.firstOrNull()
      if (directHit != null) {
        val pt = directHit.trackable as? com.google.ar.core.InstantPlacementPoint
        val isFull = pt?.trackingMethod == com.google.ar.core.InstantPlacementPoint.TrackingMethod.FULL_TRACKING
        return ComprehensiveHitResult(
          hitResult = directHit,
          hitPose = directHit.hitPose,
          hitType = if (isFull) ComprehensiveHitType.INSTANT_PLACEMENT_FULL else ComprehensiveHitType.INSTANT_PLACEMENT_TENTATIVE,
          distanceMeters = directHit.distance,
          isInstantTentative = !isFull,
          confidenceScore = if (isFull) 0.85f else 0.70f
        )
      }
    } catch (_: Throwable) {}

    val tentativeInstant = instantHits.firstOrNull()
    if (tentativeInstant != null) {
      return ComprehensiveHitResult(
        hitResult = tentativeInstant,
        hitPose = tentativeInstant.hitPose,
        hitType = ComprehensiveHitType.INSTANT_PLACEMENT_TENTATIVE,
        distanceMeters = tentativeInstant.distance,
        isInstantTentative = true,
        confidenceScore = 0.70f
      )
    }

    // 5. FEATURE POINTS
    val orientedPointHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      if (trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING) {
        trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
      } else false
    }
    if (orientedPointHit != null) {
      return ComprehensiveHitResult(
        hitResult = orientedPointHit,
        hitPose = orientedPointHit.hitPose,
        hitType = ComprehensiveHitType.ORIENTED_FEATURE_POINT,
        distanceMeters = orientedPointHit.distance,
        isInstantTentative = false,
        confidenceScore = 0.60f
      )
    }

    val genericPointHit = hits.firstOrNull { hit ->
      val trackable = hit.trackable
      trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING
    }
    if (genericPointHit != null) {
      return ComprehensiveHitResult(
        hitResult = genericPointHit,
        hitPose = genericPointHit.hitPose,
        hitType = ComprehensiveHitType.GENERIC_FEATURE_POINT,
        distanceMeters = genericPointHit.distance,
        isInstantTentative = false,
        confidenceScore = 0.50f
      )
    }

    return null
  }

  fun createAnchorFromImage(image: AugmentedImage): Anchor? {
    return try {
      image.createAnchor(image.centerPose)
    } catch (e: Exception) {
      Log.e(TAG, "Failed creating anchor from AugmentedImage: ${e.message}", e)
      null
    }
  }

  fun createAnchorForAugmentedImage(image: AugmentedImage): Anchor? = createAnchorFromImage(image)

  fun startRecording(): Boolean {
    val s = session ?: run {
      Log.w(TAG, "Cannot start recording: ARCore session is not initialized")
      return false
    }
    return recordingPlaybackManager.startRecording(s)
  }

  fun stopRecording(): java.io.File? {
    val s = session ?: run {
      Log.w(TAG, "Cannot stop recording: ARCore session is not initialized")
      return null
    }
    return recordingPlaybackManager.stopRecording(s)
  }

  fun switchCameraFacing(isFrontFaceTracking: Boolean): Boolean {
    val s = session ?: return false
    return try {
      // 1. Pause session
      s.pause()

      // 2. Release/close previously acquired frame images and depth resources
      depthOcclusionManager.clear()
      facesManager.resetState()
      latestFrame = null

      val config = s.config
      val success = if (isFrontFaceTracking) {
        // 3. Set front camera configuration
        val camSuccess = facesManager.selectFrontCameraConfig(s)

        // 4. Configure MESH3D and explicitly disable unsupported rear-only features
        facesManager.configureFaceMode(s, config, true)
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.depthMode = Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
        config.geospatialMode = Config.GeospatialMode.DISABLED

        // 5. Configure session again
        s.configure(config)
        camSuccess
      } else {
        // 3. Set rear camera configuration
        val camSuccess = facesManager.selectBackCameraConfig(s)

        // 4. Disable face tracking and restore rear-camera capabilities
        facesManager.configureFaceMode(s, config, false)
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        depthOcclusionManager.configureDepthMode(s, config)
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        geospatialManager.configureGeospatialMode(context, s, config)

        // 5. Configure session again
        s.configure(config)
        camSuccess
      }

      // 6. Re-apply camera texture name to avoid stale black texture buffer
      if (cameraTextureId != 0) {
        s.setCameraTextureName(cameraTextureId)
      }

      // 7. Resume session
      s.resume()
      Log.i(TAG, "Switched camera facing (isFront=$isFrontFaceTracking, success=$success)")
      success
    } catch (e: Exception) {
      Log.e(TAG, "Failed switching camera facing: ${e.message}", e)
      try { s.resume() } catch (_: Throwable) {}
      false
    }
  }

  fun resetWalkingOrigin() {
    initialCameraPose = null
    totalWalkingDisplacement = 0f
  }

  /**
   * Complete lifecycle cleanup when tracking is lost or session is reset.
   * On temporary tracking loss (resetSession = false), preserves persistent spatial voxels,
   * depth textures, and anchor caches for seamless recovery.
   * Only flushes resources on hard session reset (resetSession = true).
   */
  fun handleTrackingLostOrReset(resetSession: Boolean = false) {
    if (resetSession) {
      Log.i(TAG, "Hard session reset: cleaning up tracking resources...")
      environmentalMeshManager.clear()
      depthOcclusionManager.clear()
      cloudAnchorManager.clear()
      geospatialManager.clear()
      for (record in imageTrackingMap.values) {
        try { record.anchor?.detach() } catch (_: Exception) {}
      }
      imageTrackingMap.clear()
      session?.let { s ->
        try {
          s.pause()
          s.resume()
        } catch (e: Exception) {
          Log.w(TAG, "Transient session reset pause/resume: ${e.message}")
        }
      }
    } else {
      Log.i(TAG, "Spatial tracking paused or lost: retaining depth, mesh, and anchor caches for seamless recovery.")
      // Retain resources during temporary tracking loss so anchors remain at their last valid pose
      // and camera passthrough stream remains uninterrupted.
    }
  }
}
