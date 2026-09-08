package com.example.arcore

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * Production ARCore Device Capability Matrix & Compatibility Certification.
 * Distinguishes between:
 * 1. ARCore installed status
 * 2. ARCore device support status
 * 3. Official Google-certified device profile status
 * 4. Runtime feature support (Depth, Raw Depth, Geospatial, Semantics, Cloud Anchors, Faces).
 *
 * NOTE: Never infers official Google certification simply because a Session object exists.
 */
data class DeviceCapabilityCertification(
  val deviceModel: String,
  val manufacturer: String,
  val isArCoreInstalled: Boolean,
  val isArCoreSupported: Boolean,
  val isRuntimeServiceAvailable: Boolean = isArCoreInstalled && isArCoreSupported,
  val hasCertifiedHardwareProfile: Boolean = false,
  val isGoogleCertifiedDevice: Boolean = false, // Official Google certification requires Play Services server attestation, not client string matching
  val certificationTier: String,
  val supportsDepthApi: Boolean,
  val supportsRawDepth: Boolean,
  val supportsInstantPlacement: Boolean,
  val supportsGeospatialVps: Boolean,
  val supportsSceneSemantics: Boolean,
  val supportsAugmentedFaces: Boolean,
  val supportsStreetscapeGeometry: Boolean,
  val supportsCloudAnchors: Boolean,
  val recommendedQualityTier: String
)

object ArCoreDeviceMatrix {
  private const val TAG = "ArCoreDeviceMatrix"

  // Curated database of verified device families known to have Google ARCore hardware profiles
  private val KNOWN_CERTIFIED_TIER_A = listOf(
    "pixel 6", "pixel 7", "pixel 8", "pixel 9", "pixel fold", "pixel pro",
    "galaxy s21", "galaxy s22", "galaxy s23", "galaxy s24", "galaxy s25",
    "galaxy z fold", "galaxy z flip", "galaxy note 20",
    "xiaomi 12", "xiaomi 13", "xiaomi 14", "oneplus 10", "oneplus 11", "oneplus 12"
  )

  private val KNOWN_CERTIFIED_TIER_B = listOf(
    "galaxy a52", "galaxy a53", "galaxy a54", "galaxy a55",
    "pixel 6a", "pixel 7a", "pixel 8a",
    "moto g", "redmi note", "realme gt"
  )

  /**
   * Certifies current device hardware and ARCore capabilities.
   * Explicitly separates:
   * 1. ARCore installed status
   * 2. ARCore supported status
   * 3. Runtime service availability
   * 4. Known hardware profile
   * 5. Official Google certification (never claimed purely from a hardcoded list).
   */
  fun certifyDevice(context: Context, session: Session?): DeviceCapabilityCertification {
    val model = Build.MODEL ?: "Unknown"
    val manufacturer = Build.MANUFACTURER ?: "Unknown"
    val fullDeviceName = "${manufacturer.lowercase()} ${model.lowercase()}"

    // 1. Check ARCore installed status
    val arCoreInstalled = try {
      val avail = ArCoreApk.getInstance().checkAvailability(context)
      avail == ArCoreApk.Availability.SUPPORTED_INSTALLED
    } catch (_: Throwable) {
      false
    }

    // 2. Check ARCore device support status
    val arCoreSupported = try {
      val avail = ArCoreApk.getInstance().checkAvailability(context)
      avail.isSupported
    } catch (_: Throwable) {
      false
    }

    // 3. Runtime service availability
    val runtimeServiceAvailable = arCoreInstalled && arCoreSupported && (session != null)

    // 4. Hardware profile check
    val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
                     Build.FINGERPRINT.startsWith("unknown") ||
                     Build.HARDWARE.contains("goldfish") ||
                     Build.HARDWARE.contains("ranchu") ||
                     Build.PRODUCT.contains("sdk_gphone")

    val matchesCertifiedDatabase = KNOWN_CERTIFIED_TIER_A.any { fullDeviceName.contains(it) } ||
                                  KNOWN_CERTIFIED_TIER_B.any { fullDeviceName.contains(it) }

    val hasCertifiedProfile = arCoreSupported && matchesCertifiedDatabase && !isEmulator

    // Do NOT claim official Google certification based on local heuristics or custom list
    // Official Google certification requires authoritative attestation from Google Play Services.
    val isGoogleCertified = false

    val certTier = when {
      isEmulator -> "EMULATOR (CUSTOM VIRTUAL ENVIRONMENT)"
      hasCertifiedProfile && KNOWN_CERTIFIED_TIER_A.any { fullDeviceName.contains(it) } -> "CUSTOM COMPATIBILITY DETECTION: TIER-A"
      hasCertifiedProfile -> "CUSTOM COMPATIBILITY DETECTION: TIER-B"
      arCoreSupported -> "CUSTOM COMPATIBILITY DETECTION: BASIC ARCORE"
      else -> "UNSUPPORTED HARDWARE"
    }

    // 4. Real runtime capability detection strictly via ARCore session APIs
    val depthSupported = session?.let {
      try {
        it.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
      } catch (_: Throwable) { false }
    } ?: false

    val rawDepthSupported = session?.let {
      try {
        it.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
      } catch (_: Throwable) { false }
    } ?: false

    val geospatialSupported = session?.let {
      try {
        it.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
      } catch (_: Throwable) { false }
    } ?: false

    val semanticsSupported = session?.let {
      try {
        it.isSemanticModeSupported(Config.SemanticMode.ENABLED)
      } catch (_: Throwable) { false }
    } ?: false

    val streetscapeSupported = geospatialSupported

    val instantPlacementSupported = session?.let {
      try {
        val testConfig = Config(it)
        testConfig.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        it.isSupported(testConfig)
      } catch (_: Throwable) { false }
    } ?: false

    // Check front camera support for 3D Face Mesh tracking
    val facesSupported = session?.let {
      try {
        val filter = com.google.ar.core.CameraConfigFilter(it)
          .setFacingDirection(com.google.ar.core.CameraConfig.FacingDirection.FRONT)
        it.getSupportedCameraConfigs(filter).isNotEmpty()
      } catch (_: Throwable) { false }
    } ?: false

    val cloudAnchorsSupported = session?.let {
      try {
        val testConfig = Config(it)
        testConfig.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
        it.isSupported(testConfig)
      } catch (_: Throwable) { false }
    } ?: false

    // Derive tier purely from verified runtime capabilities
    val tier = when {
      session == null -> "UNINITIALIZED"
      depthSupported && semanticsSupported && geospatialSupported -> "TIER_A_ADVANCED_AR"
      depthSupported || semanticsSupported -> "TIER_B_DEPTH_ENABLED"
      else -> "TIER_C_BASIC_6DOF"
    }

    val recommendedQuality = when {
      depthSupported && semanticsSupported -> "HIGH_FIDELITY_ULTRA"
      depthSupported -> "BALANCED_HIGH"
      else -> "PERFORMANCE_STANDARD"
    }

    val certification = DeviceCapabilityCertification(
      deviceModel = model,
      manufacturer = manufacturer,
      isArCoreInstalled = arCoreInstalled,
      isArCoreSupported = arCoreSupported,
      isRuntimeServiceAvailable = runtimeServiceAvailable,
      hasCertifiedHardwareProfile = hasCertifiedProfile,
      isGoogleCertifiedDevice = isGoogleCertified,
      certificationTier = tier,
      supportsDepthApi = depthSupported,
      supportsRawDepth = rawDepthSupported,
      supportsInstantPlacement = instantPlacementSupported,
      supportsGeospatialVps = geospatialSupported,
      supportsSceneSemantics = semanticsSupported,
      supportsAugmentedFaces = facesSupported,
      supportsStreetscapeGeometry = streetscapeSupported,
      supportsCloudAnchors = cloudAnchorsSupported,
      recommendedQualityTier = recommendedQuality
    )

    Log.i(TAG, "Device Matrix: certified=$isGoogleCertified, supported=$arCoreSupported, tier=$tier (Depth=$depthSupported, Semantics=$semanticsSupported, Geospatial=$geospatialSupported)")
    return certification
  }
}
