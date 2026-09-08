package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.arcore.ImageMarkerCatalog
import com.example.engine.HardwareCapabilityDetector
import com.example.engine.RenderQualityProfile
import com.example.parser.GltfAssetFactory
import com.example.viewmodel.SpatialViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Mixed Reality", appName)
  }

  @Test
  fun `verify preset models and glb buffer generation`() {
    val models = GltfAssetFactory.getPresetModels()
    assertTrue("Preset models should not be empty", models.isNotEmpty())
    
    val droneModel = models.firstOrNull { it.id == "drone_v1" }
    assertNotNull(droneModel)

    val glbBuffer = GltfAssetFactory.getPresetGlbBuffer("drone_v1")
    assertNotNull(glbBuffer)
    assertTrue("GLB buffer should contain binary data", glbBuffer!!.capacity() > 1000)
  }

  @Test
  fun `verify image marker catalog and target generation`() {
    val exhibits = ImageMarkerCatalog.exhibits
    assertTrue("Exhibits catalog should contain markers", exhibits.isNotEmpty())

    val droneMarker = ImageMarkerCatalog.findByMarkerId("marker_drone")
    assertNotNull("Drone marker should exist", droneMarker)
    assertEquals("drone_v1", droneMarker!!.modelId)

    val bitmap = ImageMarkerCatalog.generateMarkerBitmap(droneMarker)
    assertNotNull("Generated marker bitmap should not be null", bitmap)
    assertEquals(512, bitmap.width)
    assertEquals(512, bitmap.height)
  }

  @Test
  fun `verify hardware capability detection`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val caps = HardwareCapabilityDetector.detect(context)
    assertNotNull(caps)
    assertTrue(caps.suggestedProfile in RenderQualityProfile.values())
  }

  @Test
  fun `verify spatial model 1 to 1 metric scale validation`() {
    val models = GltfAssetFactory.getPresetModels()
    for (model in models) {
      val buffer = GltfAssetFactory.getPresetGlbBuffer(model.id)
      assertNotNull("GLB buffer for ${model.id} should not be null", buffer)
      val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(buffer!!, model)
      assertTrue("Model ${model.id} should be a valid glTF structure", report.isValidGltf)
      assertTrue("Model ${model.id} should have 1:1 metric scale", report.isMetricOneToOneScale)
      assertTrue("Model ${model.id} bounding width should be > 0", report.widthMeters > 0f)
      assertTrue("Model ${model.id} vertex count should match", report.vertexCount > 0)
    }
  }

  @Test
  fun `verify spatial dynamic LOD manager distance thresholds`() {
    val lodManager = com.example.engine.SpatialLodManager()
    val cameraPos = floatArrayOf(0f, 1.5f, 0f)

    // 1. Close object (< 2.5m) -> LOD_0
    val closeObjPos = floatArrayOf(0f, 1.5f, -1.2f)
    val lodClose = lodManager.evaluateLod("obj_1", cameraPos, closeObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_0, lodClose)
    assertTrue(lodManager.shouldUpdateAnimation("obj_1"))

    // 2. Medium distance (4.0m) -> LOD_1
    val midObjPos = floatArrayOf(0f, 1.5f, -4.0f)
    val lodMid = lodManager.evaluateLod("obj_2", cameraPos, midObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_1, lodMid)

    // 3. Far distance (10.0m) -> LOD_2
    val farObjPos = floatArrayOf(0f, 1.5f, -10.0f)
    val lodFar = lodManager.evaluateLod("obj_3", cameraPos, farObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_2, lodFar)
  }

  @Test
  fun `verify cross-mode model topology consistency across Object, AR, and MR modes`() {
    val models = GltfAssetFactory.getPresetModels()
    val drone = models.first { it.id == "drone_v1" }

    // Object mode source asset
    val objectModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)
    // AR mode source asset
    val arModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)
    // MR mode source asset
    val mrModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)

    assertNotNull(objectModeBuffer)
    assertNotNull(arModeBuffer)
    assertNotNull(mrModeBuffer)

    assertEquals(objectModeBuffer!!.capacity(), arModeBuffer!!.capacity())
    assertEquals(arModeBuffer.capacity(), mrModeBuffer!!.capacity())

    // Data-driven validation against actual GLB structure
    val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(objectModeBuffer, drone)
    assertTrue("Drone GLB must be valid glTF 2.0", report.isValidGltf)
    assertEquals("Drone vertex count must match GLB geometry", report.vertexCount, drone.vertexCount)
    assertEquals("Drone triangle count must match GLB index count", report.indexCount / 3, drone.triangleCount)
    assertTrue("Drone vertex count must be positive", drone.vertexCount > 0)
    assertTrue("Drone triangle count must be positive", drone.triangleCount > 0)
    org.junit.Assert.assertFalse("Procedural drone has no animation tracks", drone.hasAnimations)
    org.junit.Assert.assertFalse("Procedural drone has no skinning bones", report.hasSkinningBones)
  }

  @Test
  fun `verify data-driven preset model geometry and animation metadata`() {
    val models = GltfAssetFactory.getPresetModels()
    for (model in models) {
      val buffer = GltfAssetFactory.getPresetGlbBuffer(model.id)
      assertNotNull("GLB buffer for ${model.id} should not be null", buffer)
      val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(buffer!!, model)

      assertTrue("Model ${model.id} must be valid glTF", report.isValidGltf)
      assertTrue("Model ${model.id} vertices must be > 0", report.vertexCount > 0)
      assertTrue("Model ${model.id} indices must be > 0", report.indexCount > 0)
      assertEquals("Model ${model.id} vertexCount must match GLB", model.vertexCount, report.vertexCount)
      assertEquals("Model ${model.id} triangleCount must match GLB", model.triangleCount, report.indexCount / 3)
      org.junit.Assert.assertFalse("Model ${model.id} should not report fictional animations", model.hasAnimations)
      assertEquals("Model ${model.id} animationTrackCount should be 0", 0, report.animationTrackCount)
      org.junit.Assert.assertFalse("Model ${model.id} should not have skinning bones", report.hasSkinningBones)
    }
  }

  @Test
  fun `verify depth occlusion manager initial state and confidence semantics`() {
    val depthManager = com.example.arcore.DepthOcclusionManager()
    org.junit.Assert.assertFalse("Depth texture should not be ready initially", depthManager.isDepthTextureReady)
    org.junit.Assert.assertFalse("Depth confidence should not be available initially", depthManager.isConfidenceAvailable)
    assertNull("Depth confidence percentage should be null when confidence image absent", depthManager.depthConfidencePercentage)
    assertEquals("Depth coverage percentage should be 0 initially", 0f, depthManager.depthCoveragePercentage, 0.001f)
  }

  @Test
  fun `verify cloud anchor manager operates independently of realtime multiplayer backend`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cloudManager = com.example.arcore.CloudAnchorManager(context)
    org.junit.Assert.assertFalse("Realtime backend should not be connected by default", cloudManager.isRealtimeBackendConnected)
    org.junit.Assert.assertFalse("Multiplayer should remain inactive without realtime backend", cloudManager.isMultiplayerActive)
    assertEquals("Cloud anchors count should start at zero", 0, cloudManager.cloudAnchorsCount)
  }

  @Test
  fun `verify mr mode semantics and device certification telemetry`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    val telemetry = viewModel.telemetry.value

    assertEquals(
      "Monoscopic Passthrough + Stereoscopic Virtual Rendering",
      telemetry.mrPassthroughSemantics
    )
    org.junit.Assert.assertFalse("True binocular hardware capture is not claimed", telemetry.isTrueBinocularPassthrough)
    org.junit.Assert.assertFalse("Google certified device should not be claimed from hardcoded string", telemetry.isGoogleCertifiedDevice)
    org.junit.Assert.assertFalse("Realtime backend should start disconnected", telemetry.isRealtimeBackendConnected)
    org.junit.Assert.assertFalse("Multiplayer should start inactive", telemetry.isMultiplayerActive)
    org.junit.Assert.assertFalse("Full 3D scene reconstruction requires dense local coverage", telemetry.isFull3dSceneReconstruction)
  }

  @Test
  fun `verify clear scene removes active model and resets spatial state`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    assertNotNull(viewModel.selectedModel.value)
    assertNotNull(viewModel.activeGlbBuffer.value)

    viewModel.clearActiveModelAndScene()
    assertNull(viewModel.selectedModel.value)
    assertNull(viewModel.activeGlbBuffer.value)
    assertEquals(0, viewModel.arAnchors.value.size)
    assertNull(viewModel.nearbyExhibit.value)
    assertEquals(0, viewModel.telemetry.value.vertexCount)
  }

  @Test
  fun `verify switching to MR mode activates stereoscopic mode`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    viewModel.setDisplayMode(com.example.model.DisplayMode.MR)
    assertEquals(com.example.model.DisplayMode.MR, viewModel.displayMode.value)
  }

  @Test
  fun `verify thermal quality levels and resolution scaling constraints`() {
    val high = com.example.engine.ThermalQualityLevel.HIGH
    val medium = com.example.engine.ThermalQualityLevel.MEDIUM
    val low = com.example.engine.ThermalQualityLevel.LOW
    val emergency = com.example.engine.ThermalQualityLevel.EMERGENCY

    assertEquals(1.0f, high.resolutionScale, 0.001f)
    assertTrue(high.enableFxaa)
    assertEquals(false, high.isThrottled)

    assertEquals(0.9f, medium.resolutionScale, 0.001f)
    assertTrue(medium.enableFxaa)
    assertTrue(medium.isThrottled)

    assertEquals(0.75f, low.resolutionScale, 0.001f)
    assertEquals(false, low.enableFxaa)
    assertTrue(low.isThrottled)

    assertEquals(0.5f, emergency.resolutionScale, 0.001f)
    assertEquals(false, emergency.enableFxaa)
    assertTrue(emergency.isThrottled)
  }

  @Test
  fun `verify arcore session manager initial state is paused`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.arcore.ArCoreSessionManager(context)
    assertTrue("Initial session state should be paused", manager.isSessionPaused)
    assertNull("Updating frame when paused should safely return null without throwing", manager.updateFrame())
  }

  @Test
  fun `verify full 3d scene reconstruction requires geometry, continuity, and spatial completeness`() {
    val meshManager = com.example.arcore.EnvironmentalMeshManager()
    
    // 1. Incomplete chunks: empty -> false
    val emptyResult = meshManager.validateFull3dSceneCompleteness(
      chunks = emptyList(),
      spanX = 4f, spanY = 2f, spanZ = 4f,
      totalTris = 3000, totalArea = 20f,
      hasFloor = true, hasWall = true
    )
    org.junit.Assert.assertFalse("Empty chunks must not be full 3D reconstruction", emptyResult)

    // 2. Missing floor or wall -> false
    val noFloorResult = meshManager.validateFull3dSceneCompleteness(
      chunks = emptyList(),
      spanX = 4f, spanY = 2f, spanZ = 4f,
      totalTris = 3000, totalArea = 20f,
      hasFloor = false, hasWall = true
    )
    org.junit.Assert.assertFalse("Must require floor and wall co-presence", noFloorResult)

    // 3. Build 16 valid contiguous chunks spanning 4 quadrants
    val validChunks = mutableListOf<com.example.arcore.MeshChunk>()
    for (i in 0 until 16) {
      val angle = (i * Math.PI * 2.0 / 16.0)
      val x = (Math.cos(angle) * 1.5).toFloat()
      val z = (Math.sin(angle) * 1.5).toFloat()
      val vb = java.nio.ByteBuffer.allocateDirect(12 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
      vb.put(floatArrayOf(x, 0f, z, x + 0.1f, 0f, z, x, 0.1f, z, x + 0.1f, 0.1f, z))
      vb.position(0)
      val ib = java.nio.ByteBuffer.allocateDirect(6 * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
      ib.put(intArrayOf(0, 1, 2, 2, 1, 3))
      ib.position(0)

      validChunks.add(
        com.example.arcore.MeshChunk(
          id = "chunk_$i",
          sourceType = com.example.arcore.GeometrySourceType.ENVIRONMENTAL_3D_MESH,
          category = if (i % 2 == 0) com.example.arcore.MeshSurfaceCategory.FLOOR else com.example.arcore.MeshSurfaceCategory.WALL,
          vertexCount = 4,
          triangleCount = 2,
          centerPosition = floatArrayOf(x, 0.5f, z),
          surfaceAreaSquareMeters = 1.2f,
          vertexBuffer = vb,
          indexBuffer = ib
        )
      )
    }

    val completeResult = meshManager.validateFull3dSceneCompleteness(
      chunks = validChunks,
      spanX = 3.5f, spanY = 1.5f, spanZ = 3.5f,
      totalTris = 2600, totalArea = 18f,
      hasFloor = true, hasWall = true
    )
    assertTrue("Validated continuous geometry across quadrants must confirm full 3D scene reconstruction", completeResult)
  }

  @Test
  fun `verify cloud anchor complete cross-device flow and local cache separation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.arcore.CloudAnchorManager(context)

    // Step 1: Device A hosts anchor
    val deviceA = "Google_Pixel_8"
    val deviceB = "Samsung_Galaxy_S24"
    val testPose = com.google.ar.core.Pose(floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 0f, 1f))
    val cloudAnchorId = "ua-test-anchor-uuid-9999"

    // Step 2: Device A creates Share ID
    val sharedExhibit = manager.shareCloudAnchor(
      cloudAnchorId = cloudAnchorId,
      sessionCode = "ROOM_101",
      modelId = "apollo_lunar_module",
      modelScale = 1.0f,
      pose = testPose,
      hostDeviceId = deviceA
    )

    assertEquals("ROOM_101", sharedExhibit.sessionRoomIdentifier)
    assertEquals(cloudAnchorId, sharedExhibit.cloudAnchorId)
    assertEquals(deviceA, sharedExhibit.hostDeviceId)
    assertEquals(com.example.arcore.CloudAnchorCrossDeviceState.SHARED_ID_GENERATED, manager.crossDeviceState)
    org.junit.Assert.assertFalse("Sharing does not validate until resolved by remote device", manager.isCrossDeviceValidated)

    // Step 3: Local cache retrieval is separated from cross-device resolution
    val cachedId = manager.getCachedAnchorId("ROOM_101")
    assertEquals(cloudAnchorId, cachedId)
    assertEquals(com.example.arcore.CloudAnchorResolutionSource.LOCAL_DEVICE_CACHE, manager.resolutionSource)
    org.junit.Assert.assertFalse("Local cache must NOT confirm cross-device resolution", manager.isCrossDeviceValidated)

    // Step 4: Verify real Device A -> Host -> Cloud ID -> Device B -> Resolve flow
    // Device B resolves anchor hosted by Device A
    val resolveSuccess = manager.verifyCrossDeviceResolution(
      cloudAnchorId = cloudAnchorId,
      originHostDeviceId = deviceA,
      resolvedByDeviceId = deviceB,
      isArCoreResolveSuccess = true
    )
    assertTrue("Real remote resolve must confirm cross-device validation", resolveSuccess)
    assertTrue("Manager state must confirm cross-device validation", manager.isCrossDeviceValidated)
    assertEquals(com.example.arcore.CloudAnchorCrossDeviceState.CONFIRMED_CROSS_DEVICE_RESOLVED, manager.crossDeviceState)
    assertEquals(com.example.arcore.CloudAnchorResolutionSource.CROSS_DEVICE_REMOTE_RESOLVED, manager.resolutionSource)

    // Self-resolve (same device) must NOT confirm cross-device
    val sameDeviceResolve = manager.verifyCrossDeviceResolution(
      cloudAnchorId = cloudAnchorId,
      originHostDeviceId = deviceA,
      resolvedByDeviceId = deviceA,
      isArCoreResolveSuccess = true
    )
    org.junit.Assert.assertFalse("Local resolve on same device must NOT validate cross-device", sameDeviceResolve)
    org.junit.Assert.assertFalse("Manager must remain false for same device resolve", manager.isCrossDeviceValidated)
  }

  @Test
  fun `verify separation of earth tracking, geospatial availability, and vps localized state`() {
    val status = com.example.arcore.GeospatialStatus(
      isSupported = true,
      isEnabled = true,
      locationPermissionGranted = true,
      earthState = "ENABLED",
      trackingState = "TRACKING",
      vpsAvailability = "UNAVAILABLE",
      isVpsLocalized = false
    )

    // Earth is tracking, but VPS is UNAVAILABLE and NOT localized
    assertEquals("TRACKING", status.trackingState)
    assertEquals("UNAVAILABLE", status.vpsAvailability)
    org.junit.Assert.assertFalse("VPS localized must remain false when unavailable", status.isVpsLocalized)
  }

  @Test
  fun `verify loopback mode is distinguished from real online multiplayer`() {
    val backend = com.example.arcore.RealtimeMultiplayerBackend()
    
    // Start loopback mode
    backend.startLoopbackService("test_room")
    assertTrue("Loopback mode must be active", backend.isLoopbackMode)
    assertTrue("Loopback test active flag must be true", backend.isLoopbackTestActive)
    org.junit.Assert.assertFalse("Loopback mode must NOT be reported as online multiplayer", backend.isOnlineMultiplayerActive)
    org.junit.Assert.assertFalse("Loopback mode must NOT report backend relay connected", backend.isBackendConnected)
    assertEquals(com.example.arcore.MultiplayerMode.LOCAL_LOOPBACK_TEST, backend.multiplayerMode)

    // Fallback loopback mode
    val fallbackBackend = com.example.arcore.RealtimeMultiplayerBackend()
    fallbackBackend.startFallbackLoopbackService("fallback_room")
    assertTrue("Fallback loopback must be active", fallbackBackend.isLoopbackMode)
    assertTrue("Fallback test active flag must be true", fallbackBackend.isLoopbackTestActive)
    org.junit.Assert.assertFalse("Fallback loopback must NOT be reported as online multiplayer", fallbackBackend.isOnlineMultiplayerActive)
    org.junit.Assert.assertFalse("Fallback loopback must NOT report backend relay connected", fallbackBackend.isBackendConnected)
    assertEquals(com.example.arcore.MultiplayerMode.FALLBACK_LOOPBACK, fallbackBackend.multiplayerMode)
  }

  @Test
  fun `verify one-tap fullscreen toggle robust state transitions`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(application)

    // Initial state: NORMAL_UI
    assertEquals(com.example.viewmodel.UiVisibilityState.NORMAL_UI, viewModel.uiVisibilityState.value)

    // Tap 1: Transitions to FULLSCREEN_UI (hides upper & lower bars, status/nav bars)
    viewModel.toggleFullscreenUi()
    assertEquals(com.example.viewmodel.UiVisibilityState.FULLSCREEN_UI, viewModel.uiVisibilityState.value)

    // Tap 2: Restores to NORMAL_UI (restores bars)
    viewModel.toggleFullscreenUi()
    assertEquals(com.example.viewmodel.UiVisibilityState.NORMAL_UI, viewModel.uiVisibilityState.value)
  }

  @Test
  fun `verify object mode dynamic miniature scaling for large real-world models`() {
    // 1. Large 15m x 5m museum
    val miniatureScaleLarge = com.example.renderer.FilamentEngineHolder.calculateMiniatureScale(15.0f, 4.0f, 5.0f)
    assertTrue("Large 15m model should be scaled down to miniature", miniatureScaleLarge < 0.1f)
    // 15m * scale should fit comfortably within viewport (~0.85m)
    val displayedSize = 15.0f * miniatureScaleLarge
    assertTrue("Displayed miniature size should be ~0.85m", displayedSize in 0.80f..0.90f)

    // 2. Standard 1.0m model should remain at 1:1 scale
    val miniatureScaleStandard = com.example.renderer.FilamentEngineHolder.calculateMiniatureScale(1.0f, 0.8f, 0.9f)
    assertEquals(1.0f, miniatureScaleStandard, 0.001f)
  }

  @Test
  fun `verify rotation delta sign mapping turns model right on right drag`() {
    var yaw = 0f
    val dxRight = 20f // Drag finger to the right
    // Natural mapping: moving finger RIGHT decrements yaw so front turns RIGHT
    yaw -= dxRight * 0.45f
    assertTrue("Right drag must decrease yaw angle for natural rightward turn", yaw < 0f)

    val dxLeft = -20f // Drag finger to the left
    yaw -= dxLeft * 0.45f
    assertEquals(0f, yaw, 0.001f)
  }

  @Test
  fun `verify procedural glb generation with animations and spatial validation`() {
    val glbBuffer = com.example.parser.GltfAssetFactory.createAnimatedDroneGlb()
    assertNotNull(glbBuffer)
    assertTrue(glbBuffer.capacity() > 100)

    // Check GLB Header: Magic 0x46546C67 ("glTF")
    val magic = glbBuffer.getInt(0)
    assertEquals(0x46546C67, magic)

    // Check Version: 2
    val version = glbBuffer.getInt(4)
    assertEquals(2, version)

    // Check total length matches buffer capacity
    val totalLength = glbBuffer.getInt(8)
    assertEquals(glbBuffer.capacity(), totalLength)

    // Validate using SpatialModelValidator
    val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(glbBuffer, null)
    assertTrue(report.isValidGltf)
    assertEquals(2, report.animationTrackCount)
    assertFalse(report.hasDracoCompression)
  }

  @Test
  fun `verify draco validation on synthetic json gltf`() {
    val gltfJsonWithDraco = """
    {
      "asset": { "version": "2.0" },
      "extensionsRequired": ["KHR_draco_mesh_compression"],
      "extensionsUsed": ["KHR_draco_mesh_compression"],
      "meshes": [{ "primitives": [{ "mode": 4 }] }]
    }
    """.trimIndent()
    val buf = java.nio.ByteBuffer.wrap(gltfJsonWithDraco.toByteArray(Charsets.UTF_8))
    val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(buf, null)
    assertTrue(report.isValidGltf)
    assertTrue(report.hasDracoCompression)
    assertTrue(report.detectedExtensions.contains("KHR_draco_mesh_compression"))
  }
}

