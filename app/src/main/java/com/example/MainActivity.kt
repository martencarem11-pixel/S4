package com.example

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.arcore.ExhibitSource
import com.example.engine.HapticManager
import com.example.model.DisplayMode
import com.example.renderer.SpatialSurfaceView
import com.example.ui.components.AnimationControlBar
import com.example.ui.components.BottomActionPill
import com.example.ui.components.CameraPassthroughView
import com.example.ui.components.DiagnosticsHud
import com.example.ui.components.ExhibitMarkerGuideSheet
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TopModePill
import com.example.ui.components.TrackingQualityIndicator
import com.example.ui.components.PlacementGuidanceOverlay
import com.example.ui.components.TrackingRecoveryCard
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SpatialViewModel
import com.example.viewmodel.UiVisibilityState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val viewModel: SpatialViewModel by viewModels()
  private var spatialSurfaceView: SpatialSurfaceView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF000000)
        ) {
          MixedRealityScreen(
            viewModel = viewModel,
            onSurfaceViewCreated = { surfaceView ->
              spatialSurfaceView = surfaceView
            }
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    spatialSurfaceView?.resume(this)
  }

  override fun onPause() {
    spatialSurfaceView?.pause()
    super.onPause()
  }

  override fun onDestroy() {
    spatialSurfaceView?.destroy()
    super.onDestroy()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixedRealityScreen(
  viewModel: SpatialViewModel,
  onSurfaceViewCreated: (SpatialSurfaceView) -> Unit
) {
  val context = LocalContext.current
  val activity = context as? ComponentActivity
  val scope = rememberCoroutineScope()
  val hapticManager = remember { HapticManager(context) }

  // Observed View Model States
  val displayMode by viewModel.displayMode.collectAsState()
  val modelsList by viewModel.modelsList.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val activeGlbBuffer by viewModel.activeGlbBuffer.collectAsState()
  val isRecording by viewModel.isRecording.collectAsState()
  val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
  val showModelSelector by viewModel.showModelSelector.collectAsState()
  val showSettings by viewModel.showSettings.collectAsState()
  val showMarkerGuide by viewModel.showMarkerGuide.collectAsState()
  val showDiagnostics by viewModel.showDiagnostics.collectAsState()
  val showGridFloor by viewModel.showGridFloor.collectAsState()
  val autoRotate by viewModel.autoRotate.collectAsState()
  val isPlayingAnimation by viewModel.isPlayingAnimation.collectAsState()
  val animationSpeed by viewModel.animationSpeed.collectAsState()
  val currentAnimationTimeSec by viewModel.currentAnimationTimeSec.collectAsState()
  val selectedAnimationTrack by viewModel.selectedAnimationTrack.collectAsState()
  val animationDurationSec by viewModel.animationDurationSec.collectAsState()
  val animationTracksCount by viewModel.animationTracksCount.collectAsState()
  val ambientIntensity by viewModel.ambientIntensity.collectAsState()
  val sunIntensity by viewModel.sunIntensity.collectAsState()
  val ipdMm by viewModel.ipdMm.collectAsState()
  val modelRollDegrees by viewModel.modelRollDegrees.collectAsState()
  val telemetry by viewModel.telemetry.collectAsState()
  val nearbyExhibit by viewModel.nearbyExhibit.collectAsState()
  val arAnchors by viewModel.arAnchors.collectAsState()
  val uiVisibilityState by viewModel.uiVisibilityState.collectAsState()

  val insetsController = remember(activity) {
    activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
  }

  // Startup Splash Screen State: Displays a clean full-screen white canvas with the app logo,
  // transitioning smoothly into MainActivity without delaying app initialization.
  var isSplashVisible by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    kotlinx.coroutines.delay(400)
    isSplashVisible = false
  }

  // Manage Android System Bars (Status & Navigation Bars) dynamically
  LaunchedEffect(uiVisibilityState, isSplashVisible) {
    insetsController?.let { controller ->
      if (isSplashVisible) {
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        controller.show(WindowInsetsCompat.Type.systemBars())
      } else {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (uiVisibilityState == UiVisibilityState.FULLSCREEN_UI) {
          controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
          controller.show(WindowInsetsCompat.Type.systemBars())
        }
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      insetsController?.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  // Sheet states
  val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val markerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // Camera Permission state for AR/MR passthrough
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    )
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasCameraPermission = granted
  }

  // GLB / glTF File Picker launcher
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri ->
    uri?.let { viewModel.loadCustomGlbFromUri(it, context) }
  }

  // Flash snapshot animation alpha
  val flashAnim = remember { Animatable(0f) }

  // Unified Filament + ARCore Spatial Surface View
  val spatialSurfaceView = remember {
    SpatialSurfaceView(context).apply {
      onTelemetryUpdate = { fps, drawCalls, vertexCount, trackingData ->
        val dims = if (filamentEngine.modelPhysicalWidthMeters > 0f) {
          "${String.format(java.util.Locale.US, "%.2f", filamentEngine.modelPhysicalWidthMeters)}m x ${String.format(java.util.Locale.US, "%.2f", filamentEngine.modelPhysicalHeightMeters)}m x ${String.format(java.util.Locale.US, "%.2f", filamentEngine.modelPhysicalDepthMeters)}m"
        } else {
          "0.00m x 0.00m x 0.00m"
        }
        viewModel.updateTelemetryFromEngine(
          fps = fps,
          drawCalls = drawCalls,
          vertexCount = vertexCount,
          trackingData = trackingData,
          depthManager = depthOcclusionManager,
          modelDimensions = dims,
          isGpuDepthOcclusionActive = filamentEngine.isGpuDepthOcclusionActive,
          isDepthTextureBoundToPipeline = filamentEngine.isDepthTextureBoundToPipeline,
          isDepthAvailable = filamentEngine.isDepthAvailable,
          isDepthTextureUploaded = filamentEngine.isDepthTextureUploaded,
          isDepthTextureBound = filamentEngine.isDepthTextureBound,
          isOcclusionShaderCompiled = filamentEngine.isOcclusionShaderCompiled,
          isOcclusionMaterialAssigned = filamentEngine.isOcclusionMaterialAssigned,
          isGpuFragmentOcclusionActive = filamentEngine.isGpuFragmentOcclusionActive,
          isGpuFragmentOcclusionRuntimeVerified = filamentEngine.isGpuFragmentOcclusionRuntimeVerified,
          gpuOcclusionState = filamentEngine.gpuOcclusionState
        )
      }
      onAnchorPlaced = { anchor, hitPos, source, modelId, modelTitle ->
        viewModel.addPlacedAnchor(
          anchorId = "anchor_${anchor.hashCode()}",
          worldPos = hitPos,
          source = source,
          modelId = modelId,
          modelTitle = modelTitle
        )
      }
      onExhibitMarkerRecognized = { marker, pos ->
        hapticManager.performDouble()
      }
      onScreenToggled = {
        hapticManager.performClick()
        viewModel.toggleFullscreenUi()
      }
      onRollDegreesChanged = { roll ->
        viewModel.setModelRollDegrees(roll)
      }
      onAssetLoaded = { count, duration, names ->
        viewModel.onAssetLoaded(count, duration, names)
      }
      onAnimationTick = { timeSec ->
        viewModel.updateAnimationTime(timeSec)
      }
      onSurfaceViewCreated(this)
    }
  }

  val isAssetLoading by viewModel.isAssetLoading.collectAsState()
  val assetLoadingProgress by viewModel.assetLoadingProgress.collectAsState()

  // Synchronize state with Filament Engine & ARCore Session
  LaunchedEffect(activeGlbBuffer, selectedModel) {
    val buf = activeGlbBuffer
    val model = selectedModel
    if (buf != null && model != null) {
      spatialSurfaceView.currentSelectedModelId = model.id
      spatialSurfaceView.currentSelectedModelTitle = model.title
      spatialSurfaceView.loadGlbBuffer(buf, model.title)
    } else {
      spatialSurfaceView.clearModelAndScene()
    }
  }

  LaunchedEffect(displayMode) {
    spatialSurfaceView.displayMode = displayMode
    if ((displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && !hasCameraPermission) {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  LaunchedEffect(showGridFloor) {
    spatialSurfaceView.filamentEngine.showGrid = showGridFloor
  }

  LaunchedEffect(autoRotate) {
    spatialSurfaceView.filamentEngine.autoRotate = autoRotate
  }

  LaunchedEffect(isPlayingAnimation) {
    spatialSurfaceView.filamentEngine.isPlayingAnimation = isPlayingAnimation
  }

  LaunchedEffect(animationSpeed) {
    spatialSurfaceView.filamentEngine.animationSpeed = animationSpeed
  }

  LaunchedEffect(ambientIntensity) {
    spatialSurfaceView.filamentEngine.ambientIntensity = ambientIntensity
  }

  LaunchedEffect(sunIntensity) {
    spatialSurfaceView.filamentEngine.sunIntensity = sunIntensity
  }

  LaunchedEffect(modelRollDegrees) {
    spatialSurfaceView.filamentEngine.modelRollDegrees = modelRollDegrees
  }

  // Toast listener
  LaunchedEffect(Unit) {
    viewModel.toastEvents.collect { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    // 0. Live Hardware Camera Passthrough (AR & MR modes)
    if (displayMode != DisplayMode.OBJECT) {
      CameraPassthroughView(
        displayMode = displayMode,
        hasCameraPermission = hasCameraPermission,
        onRequestPermission = {
          cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onDualCameraCreated = { dualView ->
          dualView.arCoreSessionManager = spatialSurfaceView.arCoreSessionManager
          dualView.depthOcclusionManager = spatialSurfaceView.depthOcclusionManager
          dualView.onCameraTextureReady = { texName ->
            spatialSurfaceView.arCoreSessionManager.setCameraTextureName(texName)
            if (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) {
              activity?.let { act ->
                spatialSurfaceView.arCoreSessionManager.resumeSession(act)
              }
            }
          }
          spatialSurfaceView.dualCameraGLSurfaceView = dualView
        },
        isArCoreActive = (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && spatialSurfaceView.arCoreSessionManager.isSupported,
        modifier = Modifier.fillMaxSize()
      )
    }

    // 1. Unified Google Filament + ARCore SurfaceView Canvas
    AndroidView(
      factory = { spatialSurfaceView },
      modifier = Modifier
        .fillMaxSize()
        .testTag("spatial_filament_canvas")
    )

    // 2. Diagnostics HUD Overlay (When explicitly enabled via settings and in NORMAL_UI)
    if (showDiagnostics && uiVisibilityState == UiVisibilityState.NORMAL_UI) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(top = 70.dp, start = 16.dp, end = 16.dp)
          .align(Alignment.TopCenter)
      ) {
        DiagnosticsHud(telemetry = telemetry)
      }
    }

    // 3. Shutter Snapshot Flash Overlay
    if (flashAnim.value > 0.01f) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = flashAnim.value))
      )
    }

    // 7. TOP CONTROLS: Mode Switcher Pill centered (MR | AR | Object)
    AnimatedVisibility(
      visible = uiVisibilityState == UiVisibilityState.NORMAL_UI,
      enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it },
      exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it },
      modifier = Modifier.align(Alignment.TopCenter)
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(top = 12.dp, start = 16.dp, end = 16.dp)
      ) {
        TopModePill(
          currentMode = displayMode,
          onModeSelected = { newMode ->
            hapticManager.performHeavy()
            viewModel.setDisplayMode(newMode)
          },
          modifier = Modifier.align(Alignment.Center)
        )
      }
    }

    var isTrackingRecoveryDismissed by remember { mutableStateOf(false) }

    // Reset dismissed state when tracking becomes valid again
    LaunchedEffect(telemetry.arTrackingStatus) {
      if (telemetry.arTrackingStatus.startsWith("TRACKING")) {
        isTrackingRecoveryDismissed = false
      }
    }

    // Explicit Tracking Recovery Affordance (Surface explicit tracking-recovery affordance when tracking lost)
    val isTrackingLost = (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) &&
      hasCameraPermission &&
      uiVisibilityState == UiVisibilityState.NORMAL_UI &&
      !isTrackingRecoveryDismissed &&
      !telemetry.arTrackingStatus.startsWith("TRACKING") &&
      telemetry.arTrackingStatus != "UNINITIALIZED" &&
      (telemetry.activeAnchorsCount > 0 || telemetry.arTrackingStatus.startsWith("PAUSED"))

    TrackingRecoveryCard(
      isVisible = isTrackingLost,
      trackingStatus = telemetry.arTrackingStatus,
      onRecenterClick = {
        hapticManager.performClick()
        spatialSurfaceView.arCoreSessionManager.resetWalkingOrigin()
        isTrackingRecoveryDismissed = true
      },
      onDismiss = {
        hapticManager.performClick()
        isTrackingRecoveryDismissed = true
      },
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(top = 76.dp)
        .align(Alignment.TopCenter)
    )

    // 8. BOTTOM CONTROLS: Floating Action Pill [ PHOTO | (● REC) | Open | Clear ]
    AnimatedVisibility(
      visible = uiVisibilityState == UiVisibilityState.NORMAL_UI,
      enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it },
      exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it },
      modifier = Modifier.align(Alignment.BottomCenter)
    ) {
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(bottom = 24.dp)
      ) {
        BottomActionPill(
          isRecording = isRecording,
          recordingDurationSec = recordingDurationSec,
          onPhotoClick = {
            hapticManager.performDouble()
            scope.launch {
              flashAnim.snapTo(0.85f)
              flashAnim.animateTo(0f, tween(350))
            }
            spatialSurfaceView.captureSnapshot(
              onCaptured = { bmp ->
                viewModel.saveSnapshot(bmp, context)
              },
              onError = { errMsg ->
                viewModel.log("SNAPSHOT_ERR", errMsg)
              }
            )
          },
          onRecClick = {
            hapticManager.performHeavy()
            viewModel.toggleRecording(spatialSurfaceView.arCoreSessionManager)
          },
          onOpenClick = {
            hapticManager.performClick()
            filePickerLauncher.launch("*/*")
          },
          onClearClick = {
            hapticManager.performHeavy()
            spatialSurfaceView.clearModelAndScene()
            viewModel.clearActiveModelAndScene()
          }
        )
      }
    }

    // 8. Sheets for Model Selector, Marker Guide & Settings
    if (showModelSelector) {
      ModelSelectorSheet(
        sheetState = modelSheetState,
        models = modelsList,
        selectedModel = selectedModel,
        onSelectModel = { model ->
          hapticManager.performClick()
          viewModel.selectModel(model)
          viewModel.setShowModelSelector(false)
        },
        onPickCustomFile = {
          viewModel.setShowModelSelector(false)
          filePickerLauncher.launch("*/*")
        },
        onDismiss = { viewModel.setShowModelSelector(false) }
      )
    }

    if (showMarkerGuide) {
      ExhibitMarkerGuideSheet(
        sheetState = markerSheetState,
        onSelectModel = { modelId ->
          hapticManager.performClick()
          val model = modelsList.firstOrNull { it.id == modelId }
          if (model != null) {
            viewModel.selectModel(model)
          }
          viewModel.setShowMarkerGuide(false)
        },
        onDismiss = { viewModel.setShowMarkerGuide(false) }
      )
    }

    if (showSettings) {
      SettingsSheet(
        sheetState = settingsSheetState,
        ambientIntensity = ambientIntensity,
        onAmbientChange = { viewModel.setAmbientIntensity(it) },
        sunIntensity = sunIntensity,
        onSunChange = { viewModel.setSunIntensity(it) },
        showGridFloor = showGridFloor,
        onGridFloorChange = { viewModel.setShowGridFloor(it) },
        autoRotate = autoRotate,
        onAutoRotateChange = { viewModel.setAutoRotate(it) },
        ipdMm = ipdMm,
        onIpdChange = { viewModel.setIpdMm(it) },
        showDiagnostics = showDiagnostics,
        onDiagnosticsChange = { viewModel.setShowDiagnostics(it) },
        modelRollDegrees = modelRollDegrees,
        onRollChange = { viewModel.setModelRollDegrees(it) },
        onResetScene = {
          spatialSurfaceView.resetView()
          viewModel.setModelRollDegrees(0f)
          viewModel.resetOrRestoreModel()
          viewModel.setShowSettings(false)
        },
        onDismiss = { viewModel.setShowSettings(false) }
      )
    }

    // 9. Streaming 3D Model Loading Progress for Large Files (250MB+)
    if (isAssetLoading) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.70f))
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E293B))
            .padding(28.dp)
        ) {
          CircularProgressIndicator(
            progress = { assetLoadingProgress },
            color = Color(0xFF38BDF8),
            modifier = Modifier.size(48.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Loading 3D Model... ${(assetLoadingProgress * 100).toInt()}%",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
          Text(
            text = "Streaming large asset into memory",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }
    }

    // 10. Startup Splash Screen Transition (Pure White background with centered app logo)
    AnimatedVisibility(
      visible = isSplashVisible,
      exit = fadeOut(tween(350))
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White)
      ) {
        Image(
          painter = painterResource(id = R.drawable.ic_mr_logo),
          contentDescription = "App Logo",
          modifier = Modifier.size(120.dp)
        )
      }
    }
  }
}
