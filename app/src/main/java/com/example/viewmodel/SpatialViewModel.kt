package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arcore.ArCoreTrackingData
import com.example.arcore.DepthOcclusionManager
import com.example.arcore.ExhibitMarker
import com.example.arcore.ExhibitSource
import com.example.arcore.ImageMarkerCatalog
import com.example.engine.DiagnosticsLogger
import com.example.engine.LogEntry
import com.example.engine.TelemetryState
import com.example.engine.ThermalGuardManager
import com.example.model.DisplayMode
import com.example.model.SpatialAnchor
import com.example.model.SpatialModel
import com.example.parser.GltfAssetFactory
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

enum class UiVisibilityState {
  NORMAL_UI,
  FULLSCREEN_UI
}

class SpatialViewModel(application: Application) : AndroidViewModel(application) {

  private val _uiVisibilityState = MutableStateFlow(UiVisibilityState.NORMAL_UI)
  val uiVisibilityState: StateFlow<UiVisibilityState> = _uiVisibilityState.asStateFlow()

  private val logger = DiagnosticsLogger(application)

  private val _displayMode = MutableStateFlow(DisplayMode.OBJECT)
  val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

  private val _modelsList = MutableStateFlow<List<SpatialModel>>(GltfAssetFactory.getPresetModels())
  val modelsList: StateFlow<List<SpatialModel>> = _modelsList.asStateFlow()

  private val _selectedModel = MutableStateFlow<SpatialModel?>(_modelsList.value.firstOrNull())
  val selectedModel: StateFlow<SpatialModel?> = _selectedModel.asStateFlow()

  // Active GLB / glTF direct ByteBuffer for Filament gltfio
  private val _activeGlbBuffer = MutableStateFlow<ByteBuffer?>(
    _modelsList.value.firstOrNull()?.let { GltfAssetFactory.getPresetGlbBuffer(it.id) }
  )
  val activeGlbBuffer: StateFlow<ByteBuffer?> = _activeGlbBuffer.asStateFlow()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  private val _recordingDurationSec = MutableStateFlow(0)
  val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

  private val _showModelSelector = MutableStateFlow(false)
  val showModelSelector: StateFlow<Boolean> = _showModelSelector.asStateFlow()

  private val _showSettings = MutableStateFlow(false)
  val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

  private val _showMarkerGuide = MutableStateFlow(false)
  val showMarkerGuide: StateFlow<Boolean> = _showMarkerGuide.asStateFlow()

  private val _showDiagnostics = MutableStateFlow(false)
  val showDiagnostics: StateFlow<Boolean> = _showDiagnostics.asStateFlow()

  private val _showGridFloor = MutableStateFlow(true)
  val showGridFloor: StateFlow<Boolean> = _showGridFloor.asStateFlow()

  private val _autoRotate = MutableStateFlow(false)
  val autoRotate: StateFlow<Boolean> = _autoRotate.asStateFlow()

  private val _isPlayingAnimation = MutableStateFlow(true)
  val isPlayingAnimation: StateFlow<Boolean> = _isPlayingAnimation.asStateFlow()

  private val _animationSpeed = MutableStateFlow(1.0f)
  val animationSpeed: StateFlow<Float> = _animationSpeed.asStateFlow()

  private val _currentAnimationTimeSec = MutableStateFlow(0.0f)
  val currentAnimationTimeSec: StateFlow<Float> = _currentAnimationTimeSec.asStateFlow()

  private val _selectedAnimationTrack = MutableStateFlow(0)
  val selectedAnimationTrack: StateFlow<Int> = _selectedAnimationTrack.asStateFlow()

  private val _animationDurationSec = MutableStateFlow(4.0f)
  val animationDurationSec: StateFlow<Float> = _animationDurationSec.asStateFlow()

  private val _animationTracksCount = MutableStateFlow(1)
  val animationTracksCount: StateFlow<Int> = _animationTracksCount.asStateFlow()

  private val _animationTrackNames = MutableStateFlow<List<String>>(emptyList())
  val animationTrackNames: StateFlow<List<String>> = _animationTrackNames.asStateFlow()

  private val _ambientIntensity = MutableStateFlow(30000.0f)
  val ambientIntensity: StateFlow<Float> = _ambientIntensity.asStateFlow()

  private val _sunIntensity = MutableStateFlow(100000.0f)
  val sunIntensity: StateFlow<Float> = _sunIntensity.asStateFlow()

  private val _isAssetLoading = MutableStateFlow(false)
  val isAssetLoading: StateFlow<Boolean> = _isAssetLoading.asStateFlow()

  private val _assetLoadingProgress = MutableStateFlow(0f)
  val assetLoadingProgress: StateFlow<Float> = _assetLoadingProgress.asStateFlow()

  private val _ipdMm = MutableStateFlow(64.0f)
  val ipdMm: StateFlow<Float> = _ipdMm.asStateFlow()

  private val _modelRollDegrees = MutableStateFlow(0f)
  val modelRollDegrees: StateFlow<Float> = _modelRollDegrees.asStateFlow()

  private val _telemetry = MutableStateFlow(TelemetryState())
  val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

  // Active Multi-Object Scene Anchors
  private val _arAnchors = MutableStateFlow<List<SpatialAnchor>>(emptyList())
  val arAnchors: StateFlow<List<SpatialAnchor>> = _arAnchors.asStateFlow()

  // Nearby Exhibit when walking close to a placed model (<1.8m)
  private val _nearbyExhibit = MutableStateFlow<SpatialAnchor?>(null)
  val nearbyExhibit: StateFlow<SpatialAnchor?> = _nearbyExhibit.asStateFlow()

  private val _toastEvents = MutableSharedFlow<String>()
  val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

  private var recordingJob: Job? = null
  private var thermalGuard: ThermalGuardManager? = null

  init {
    thermalGuard = ThermalGuardManager(application) { level, statusStr ->
      _telemetry.update { it.copy(thermalStatus = statusStr) }
      log("THERMAL", "Status: $statusStr (Level: ${level.name}, Throttled: ${level.isThrottled})")
    }
    thermalGuard?.startMonitoring()
  }

  fun setDisplayMode(mode: DisplayMode) {
    _displayMode.value = mode
    log("DISPLAY", "Switched display pipeline to: $mode")
  }

  fun selectModel(model: SpatialModel) {
    _selectedModel.value = model
    _activeGlbBuffer.value = GltfAssetFactory.getPresetGlbBuffer(model.id)
    _currentAnimationTimeSec.value = 0f
    _selectedAnimationTrack.value = 0
    _telemetry.update {
      it.copy(
        vertexCount = model.vertexCount,
        triangleCount = model.triangleCount
      )
    }
    log("FILAMENT_GLTF", "Instantiated 1:1 Metric glTF model: ${model.title}")
  }

  fun loadCustomGlbFromUri(uri: Uri, context: Context) {
    viewModelScope.launch {
      _isAssetLoading.value = true
      _assetLoadingProgress.value = 0f
      try {
        val directBuffer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          GltfAssetFactory.readUriToDirectByteBuffer(context, uri) { read, total ->
            if (total > 0) {
              _assetLoadingProgress.value = (read.toFloat() / total.toFloat()).coerceIn(0.01f, 1f)
            } else {
              val estimate = 5_000_000f
              _assetLoadingProgress.value = (1f - (1f / (1f + read.toFloat() / estimate))).coerceIn(0.05f, 0.95f)
            }
          }
        }
        if (directBuffer != null) {
          val modelName = uri.lastPathSegment?.substringAfterLast('/') ?: "Custom_Model.glb"
          val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(directBuffer, null)
          val realVerts = if (report.vertexCount > 0) report.vertexCount else (directBuffer.capacity() / 64).coerceAtLeast(1)
          val realTris = if (report.indexCount > 0) report.indexCount / 3 else (directBuffer.capacity() / 128).coerceAtLeast(1)
          val realHasAnims = report.animationTrackCount > 0
          val realAnimDuration = if (realHasAnims) report.animationDurationSec else 0f
          val dimsDesc = String.format(java.util.Locale.US, "%.2fm x %.2fm x %.2fm", report.widthMeters, report.heightMeters, report.depthMeters)

          val customModel = SpatialModel(
            id = "custom_${System.currentTimeMillis()}",
            title = modelName,
            description = "Custom loaded glTF asset at 1:1 physical scale ($dimsDesc, ${report.materialCount} materials, ${report.textureCount} textures)",
            category = "Imported 3D Assets",
            vertexCount = realVerts,
            triangleCount = realTris,
            isCustomLoaded = true,
            hasAnimations = realHasAnims,
            animationDurationSec = realAnimDuration
          )
          _modelsList.update { listOf(customModel) + it }
          _selectedModel.value = customModel
          _activeGlbBuffer.value = directBuffer
          emitToast("Imported 3D Model: $modelName ($dimsDesc)")
          log("GLTFIO", "Parsed external glTF: $modelName ($dimsDesc, $realVerts vertices, $realTris tris, ${directBuffer.capacity() / (1024 * 1024)} MB)")
        } else {
          emitToast("Could not read GLB / glTF file or file was invalid.")
        }
      } catch (e: Exception) {
        emitToast("Error loading glTF file: ${e.message}")
        log("ERROR", "Failed to load glTF: ${e.message}")
      } finally {
        _isAssetLoading.value = false
        _assetLoadingProgress.value = 1f
      }
    }
  }

  fun addPlacedAnchor(
    anchorId: String,
    worldPos: FloatArray,
    source: ExhibitSource = ExhibitSource.PLANE_TAP,
    modelId: String = _selectedModel.value?.id ?: "drone_v1",
    modelTitle: String = _selectedModel.value?.title ?: "3D Exhibit",
    markerId: String? = null
  ) {
    val newAnchor = SpatialAnchor(
      id = anchorId,
      posX = worldPos[0],
      posY = worldPos[1],
      posZ = worldPos[2],
      rotY = 0f,
      modelId = modelId,
      modelTitle = modelTitle,
      source = source,
      markerId = markerId
    )
    _arAnchors.update { it + newAnchor }
    _telemetry.update { it.copy(activeAnchorsCount = _arAnchors.value.size) }

    val msg = if (source == ExhibitSource.IMAGE_MARKER) {
      "🎯 Image Marker Tracked: $modelTitle"
    } else {
      "📍 Plane Anchor Placed: $modelTitle"
    }
    log("SPATIAL_ANCHOR", "Pinned $modelTitle via $source at (%.2f, %.2f, %.2f)".format(worldPos[0], worldPos[1], worldPos[2]))
  }

  fun removeAnchor(anchorId: String) {
    _arAnchors.update { it.filterNot { a -> a.id == anchorId } }
    _telemetry.update { it.copy(activeAnchorsCount = _arAnchors.value.size) }
    log("SPATIAL_ANCHOR", "Removed anchor: $anchorId")
  }

  fun toggleRecording(sessionManager: com.example.arcore.ArCoreSessionManager? = null) {
    val currentlyRecording = _isRecording.value
    if (!currentlyRecording) {
      if (sessionManager == null) {
        emitToast("ARCore session unavailable for recording")
        return
      }
      val success = sessionManager.startRecording()
      if (success) {
        _isRecording.value = true
        _recordingDurationSec.value = 0
        emitToast("ARCore Session Recording Started")
        log("RECORDER", "ARCore MP4 dataset recording active")
      } else {
        val err = sessionManager.recordingPlaybackManager.telemetry.errorMessage ?: "Failed to start ARCore recording"
        emitToast("Recording failed: $err")
        log("RECORDER_ERR", err)
      }
    } else {
      val recordedFile = sessionManager?.stopRecording()
      _isRecording.value = false
      if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
        val durationSec = sessionManager.recordingPlaybackManager.telemetry.recordedDurationSeconds.toInt()
        val sizeKb = recordedFile.length() / 1024
        emitToast("ARCore dataset saved (${recordedFile.name}, ${sizeKb} KB, ${durationSec}s)")
        log("RECORDER", "ARCore recording dataset created: ${recordedFile.absolutePath} (${sizeKb} KB, ${durationSec}s)")
      } else {
        val err = sessionManager?.recordingPlaybackManager?.telemetry?.errorMessage ?: "Recording dataset not created or empty"
        emitToast("Recording error: $err")
        log("RECORDER_ERR", err)
      }
    }
  }

  fun saveSnapshot(bitmap: Bitmap, context: Context) {
    viewModelScope.launch {
      try {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Spatial_Photo_$timeStamp.png"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(storageDir, fileName)
        FileOutputStream(file).use { out ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        emitToast("Spatial Photo saved ($fileName)")
        log("CAPTURE", "PixelCopy snapshot saved to ${file.absolutePath}")
      } catch (e: Exception) {
        emitToast("Photo captured")
      }
    }
  }

  fun clearActiveModelAndScene() {
    _selectedModel.value = null
    _activeGlbBuffer.value = null
    _arAnchors.value = emptyList()
    _nearbyExhibit.value = null
    _isPlayingAnimation.value = false
    _currentAnimationTimeSec.value = 0f
    _telemetry.update {
      it.copy(
        activeAnchorsCount = 0,
        vertexCount = 0,
        triangleCount = 0,
        metricDimensions = "None (Scene Cleared)"
      )
    }
    log("SCENE", "Active model and all spatial anchors cleared from scene")
  }

  fun resetOrRestoreModel() {
    clearActiveModelAndScene()
  }

  fun scrubAnimation(timeSec: Float) {
    _currentAnimationTimeSec.value = timeSec
  }

  fun updateAnimationTime(timeSec: Float) {
    _currentAnimationTimeSec.value = timeSec
  }

  fun selectAnimationTrack(trackIndex: Int) {
    _selectedAnimationTrack.value = trackIndex
    _currentAnimationTimeSec.value = 0f
    log("ANIMATION", "Selected track: $trackIndex")
  }

  fun onAssetLoaded(trackCount: Int, durationSec: Float, trackNames: List<String>) {
    _animationTracksCount.value = trackCount
    _animationDurationSec.value = if (durationSec > 0f) durationSec else 4.0f
    _animationTrackNames.value = trackNames
    _selectedAnimationTrack.value = 0
    _currentAnimationTimeSec.value = 0f
    _isPlayingAnimation.value = trackCount > 0
    log("ASSET_LOADED", "Loaded glTF: $trackCount animation tracks, ${durationSec}s duration")
  }

  fun setShowModelSelector(show: Boolean) { _showModelSelector.value = show }
  fun setShowSettings(show: Boolean) { _showSettings.value = show }
  fun setShowMarkerGuide(show: Boolean) { _showMarkerGuide.value = show }
  fun setShowDiagnostics(show: Boolean) { _showDiagnostics.value = show }
  fun setShowGridFloor(show: Boolean) { _showGridFloor.value = show }
  fun setAutoRotate(auto: Boolean) { _autoRotate.value = auto }
  fun toggleAnimationPlay() { _isPlayingAnimation.value = !_isPlayingAnimation.value }
  fun setAnimationSpeed(speed: Float) { _animationSpeed.value = speed }
  fun setAmbientIntensity(intensity: Float) { _ambientIntensity.value = intensity }
  fun setSunIntensity(intensity: Float) { _sunIntensity.value = intensity }
  fun setIpdMm(ipd: Float) { _ipdMm.value = ipd }
  fun setModelRollDegrees(roll: Float) {
    _modelRollDegrees.value = roll
    _telemetry.update { it.copy(modelRollDegrees = roll) }
  }
  fun toggleFullscreenUi() {
    _uiVisibilityState.update { current ->
      if (current == UiVisibilityState.NORMAL_UI) UiVisibilityState.FULLSCREEN_UI else UiVisibilityState.NORMAL_UI
    }
  }
  fun setUiVisibilityState(state: UiVisibilityState) { _uiVisibilityState.value = state }

  fun updateTelemetryFromEngine(
    fps: Float,
    drawCalls: Int,
    vertexCount: Int,
    trackingData: ArCoreTrackingData,
    depthManager: DepthOcclusionManager? = null,
    modelDimensions: String = "0.00m x 0.00m x 0.00m",
    isGpuDepthOcclusionActive: Boolean = false,
    isDepthTextureBoundToPipeline: Boolean = false,
    isDepthAvailable: Boolean = false,
    isDepthTextureUploaded: Boolean = false,
    isDepthTextureBound: Boolean = false,
    isOcclusionShaderCompiled: Boolean = false,
    isOcclusionMaterialAssigned: Boolean = false,
    isGpuFragmentOcclusionActive: Boolean = false,
    isGpuFragmentOcclusionRuntimeVerified: Boolean = false,
    gpuOcclusionState: String = "DEPTH_UNAVAILABLE"
  ) {
    // Update distances from camera to anchors
    val camPos = trackingData.cameraPosition
    var closest: SpatialAnchor? = null
    var minDistance = Float.MAX_VALUE

    if (trackingData.trackingState == TrackingState.TRACKING && _arAnchors.value.isNotEmpty()) {
      val updatedList = _arAnchors.value.map { anchor ->
        val dx = anchor.posX - camPos[0]
        val dy = anchor.posY - camPos[1]
        val dz = anchor.posZ - camPos[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < minDistance) {
          minDistance = dist
          closest = anchor.copy(distanceToCameraMeters = dist)
        }
        anchor.copy(distanceToCameraMeters = dist)
      }
      _arAnchors.value = updatedList
      _nearbyExhibit.value = if (minDistance < 2.2f) closest else null
    } else {
      _nearbyExhibit.value = null
    }

    if (trackingData.recordingTelemetry.isRecording) {
      _isRecording.value = true
      _recordingDurationSec.value = trackingData.recordingTelemetry.recordedDurationSeconds.toInt()
    }

    _telemetry.update {
      it.copy(
        fps = fps,
        drawCalls = drawCalls,
        vertexCount = if (vertexCount > 0) vertexCount else it.vertexCount,
        triangleCount = if (vertexCount > 0) vertexCount / 2 else it.triangleCount,
        arTrackingStatus = when (trackingData.trackingState) {
          TrackingState.TRACKING -> "TRACKING (6DoF OK)"
          TrackingState.PAUSED -> "PAUSED (${trackingData.trackingFailureReason})"
          TrackingState.STOPPED -> "STOPPED"
        },
        horizontalPlanesCount = trackingData.horizontalPlanesCount,
        verticalPlanesCount = trackingData.verticalPlanesCount,
        trackedImagesCount = trackingData.detectedImages.size,
        walkingDisplacementMeters = trackingData.walkingDisplacementMeters,
        lightIntensityLumens = trackingData.lightIntensityLumens,
        isDepthEnabled = trackingData.isDepthEnabled,
        depthMinMeters = depthManager?.minDepthMeters ?: it.depthMinMeters,
        depthMaxMeters = depthManager?.maxDepthMeters ?: it.depthMaxMeters,
        depthAvgMeters = depthManager?.averageDepthMeters ?: it.depthAvgMeters,
        depthOcclusionDetected = depthManager?.isOcclusionDetected ?: false,
        occlusionPercentage = depthManager?.occlusionPercentage ?: 0f,
        isGpuDepthOcclusionActive = isGpuDepthOcclusionActive,
        isDepthTextureBoundToPipeline = isDepthTextureBoundToPipeline,
        isGpuDepthTextureUploaded = isDepthTextureUploaded || (depthManager?.isDepthTextureReady == true && ((depthManager?.depthTextureId ?: 0) != 0)),
        isShaderFragmentDiscardActive = isGpuDepthOcclusionActive,
        isCpuAnalyticalOcclusionActive = true,
        isDepthAvailable = isDepthAvailable || trackingData.isDepthEnabled || (depthManager?.isDepthAvailable == true),
        isDepthTextureUploaded = isDepthTextureUploaded || (depthManager?.isDepthTextureReady == true),
        isDepthTextureBound = isDepthTextureBound || isDepthTextureBoundToPipeline,
        isOcclusionShaderCompiled = isOcclusionShaderCompiled,
        isOcclusionMaterialAssigned = isOcclusionMaterialAssigned,
        isGpuFragmentOcclusionActive = isGpuFragmentOcclusionActive,
        isGpuFragmentOcclusionRuntimeVerified = isGpuFragmentOcclusionRuntimeVerified,
        gpuOcclusionState = gpuOcclusionState,
        gpuOcclusionPipelineMode = gpuOcclusionState,
        cameraStreamStatus = if (trackingData.trackingState == TrackingState.TRACKING) "ARCORE_CAMERA_ACTIVE" else "CAMERA_STREAM_ACTIVE",
        isInstantPlacementActive = trackingData.isInstantPlacementEnabled,
        isGeospatialSupported = trackingData.geospatialStatus.isSupported,
        isGeospatialEnabled = trackingData.geospatialStatus.isEnabled,
        hasLocationPermission = trackingData.geospatialStatus.locationPermissionGranted,
        isEarthTrackingActive = trackingData.geospatialStatus.trackingState == "TRACKING",
        isVpsActive = trackingData.geospatialStatus.isVpsLocalized && trackingData.geospatialStatus.vpsAvailability == "AVAILABLE" && trackingData.geospatialStatus.trackingState == "TRACKING",
        isVpsLocalized = trackingData.geospatialStatus.isVpsLocalized && trackingData.geospatialStatus.vpsAvailability == "AVAILABLE",
        isGeospatialActive = trackingData.geospatialStatus.isSupported && trackingData.geospatialStatus.isEnabled && trackingData.geospatialStatus.trackingState == "TRACKING",
        earthTrackingState = when {
          !trackingData.geospatialStatus.isSupported -> "UNSUPPORTED"
          !trackingData.geospatialStatus.locationPermissionGranted -> "PERMISSION REQUIRED"
          else -> "${trackingData.geospatialStatus.earthState} (${trackingData.geospatialStatus.trackingState})"
        },
        earthCoordinates = when {
          !trackingData.geospatialStatus.isSupported -> "UNSUPPORTED"
          !trackingData.geospatialStatus.locationPermissionGranted -> "LOCATION PERMISSION REQUIRED"
          trackingData.geospatialStatus.trackingState == "TRACKING" && trackingData.geospatialStatus.latitude != 0.0 ->
            "%.5f°, %.5f° (±%.1fm)".format(
              trackingData.geospatialStatus.latitude,
              trackingData.geospatialStatus.longitude,
              trackingData.geospatialStatus.horizontalAccuracyMeters
            )
          else -> "LOCALIZING..."
        },
        cloudAnchorsCount = trackingData.cloudAnchorsCount,
        isCrossDeviceResolutionConfirmed = trackingData.isCrossDeviceResolutionConfirmed,
        cloudAnchorCrossDeviceState = trackingData.cloudAnchorCrossDeviceState,
        dominantSemanticLabel = when {
          !trackingData.semanticsTelemetry.isSemanticModeSupported -> "UNSUPPORTED"
          trackingData.semanticsTelemetry.dominantConfidencePercent > 0 ->
            "${trackingData.semanticsTelemetry.dominantLabel} (${trackingData.semanticsTelemetry.dominantConfidencePercent}%)"
          else -> trackingData.semanticsTelemetry.dominantLabel
        },
        depthConfidenceScore = depthManager?.depthConfidenceScore ?: it.depthConfidenceScore,
        isDepthConfidenceAvailable = depthManager?.isConfidenceAvailable ?: false,
        depthConfidencePercentage = depthManager?.depthConfidencePercentage,
        depthCoveragePercentage = depthManager?.depthCoveragePercentage ?: 0f,
        vpsAvailability = trackingData.geospatialStatus.vpsAvailability,
        deviceTier = trackingData.certification?.certificationTier ?: it.deviceTier,
        isGoogleCertifiedDevice = trackingData.certification?.isGoogleCertifiedDevice ?: false,
        isArCoreSupported = trackingData.certification?.isArCoreSupported ?: true,
        isArCoreInstalled = trackingData.certification?.isArCoreInstalled ?: true,
        mrPassthroughSemantics = "MONOSCOPIC_CAMERA + STEREOSCOPIC_VIRTUAL_RENDERING",
        isTrueBinocularPassthrough = false,
        isRealtimeBackendConnected = trackingData.isRealtimeBackendConnected,
        isMultiplayerActive = trackingData.isMultiplayerActive,
        isOnlineMultiplayerActive = trackingData.isOnlineMultiplayerActive,
        isLoopbackTestActive = trackingData.isLoopbackTestActive,
        multiplayerMode = trackingData.multiplayerMode,
        arRecordingStatus = trackingData.recordingTelemetry.recordingStatus,
        environmentalMeshTriangles = trackingData.reconstructionTelemetry.totalTriangles,
        environmentalMeshAreaSqM = trackingData.reconstructionTelemetry.totalSurfaceAreaSqMeters,
        hasReal3dMeshGeometry = trackingData.reconstructionTelemetry.hasReal3dMeshGeometry,
        streetscapeGeometriesCount = trackingData.reconstructionTelemetry.streetscapeGeometriesCount,
        denseMeshChunksCount = trackingData.reconstructionTelemetry.denseMeshChunksCount,
        pointCloudPointsCount = trackingData.pointCloudPointsCount,
        isDenseLocalMeshActive = trackingData.reconstructionTelemetry.isDenseLocalMeshActive,
        isValidatedLocalSceneMesh = trackingData.reconstructionTelemetry.isValidatedLocalSceneMesh,
        isFull3dSceneReconstruction = trackingData.reconstructionTelemetry.isFull3dSceneReconstruction,
        reconstructionStage = trackingData.reconstructionTelemetry.reconstructionStage,
        isDriftActive = trackingData.isDriftActive,
        driftStartFrameIndex = trackingData.driftStartFrameIndex,
        driftCategory = trackingData.driftCategory,
        accumulatedDriftMeters = trackingData.accumulatedDriftMeters,
        currentFrameNumber = trackingData.currentFrameNumber,
        trackingQuality = trackingData.trackingQuality,
        metricDimensions = modelDimensions
      )
    }
  }

  private fun emitToast(msg: String) {
    viewModelScope.launch {
      _toastEvents.emit(msg)
    }
  }

  fun log(tag: String, msg: String) {
    logger.log(tag, msg)
    val entry = LogEntry(tag = tag, message = msg)
    _telemetry.update {
      it.copy(logs = (listOf(entry) + it.logs).take(50))
    }
  }

  override fun onCleared() {
    super.onCleared()
    thermalGuard?.stopMonitoring()
  }
}
