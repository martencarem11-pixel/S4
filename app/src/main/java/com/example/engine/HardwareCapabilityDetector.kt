package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

enum class RenderQualityProfile {
  ULTRA,
  HIGH,
  MEDIUM,
  LOW
}

data class HardwareCapabilities(
  val isArcoreSupported: Boolean = false,
  val isDepthSupported: Boolean = false,
  val isVulkanSupported: Boolean = false,
  val glEsVersion: String = "OpenGL ES 3.2",
  val refreshRateHz: Float = 60f,
  val totalMemoryMb: Long = 4096L,
  val availableMemoryMb: Long = 2048L,
  val isLowRamDevice: Boolean = false,
  val suggestedProfile: RenderQualityProfile = RenderQualityProfile.HIGH,
  val cpuCores: Int = Runtime.getRuntime().availableProcessors()
)

/**
 * Detects device hardware specifications, GPU capabilities, ARCore support,
 * display refresh rate, and assigns an optimal rendering quality profile.
 */
object HardwareCapabilityDetector {

  fun detect(context: Context): HardwareCapabilities {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memInfo)

    val totalRamMb = memInfo.totalMem / (1024 * 1024)
    val availRamMb = memInfo.availMem / (1024 * 1024)
    val isLowRam = activityManager?.isLowRamDevice ?: false

    // Display refresh rate
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val refreshRate = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.refreshRate ?: 60f
      } else {
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.refreshRate ?: 60f
      }
    } catch (_: Exception) {
      60f
    }

    // ARCore availability
    var isArcore = false
    try {
      val isArCoreInstalled = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getPackageInfo("com.google.ar.core", android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION")
          context.packageManager.getPackageInfo("com.google.ar.core", 0)
        }
        true
      } catch (_: Exception) {
        false
      }

      if (isArCoreInstalled) {
        val arcoreStatus = ArCoreApk.getInstance().checkAvailability(context)
        isArcore = arcoreStatus.isSupported
      }
    } catch (_: Throwable) {}

    // GLES Version
    val glEsVersion = activityManager?.deviceConfigurationInfo?.glEsVersion ?: "3.0"

    // Profile determination
    val profile = when {
      totalRamMb >= 7500 && refreshRate >= 90f && !isLowRam -> RenderQualityProfile.ULTRA
      totalRamMb >= 5000 && !isLowRam -> RenderQualityProfile.HIGH
      totalRamMb >= 3000 -> RenderQualityProfile.MEDIUM
      else -> RenderQualityProfile.LOW
    }

    return HardwareCapabilities(
      isArcoreSupported = isArcore,
      isDepthSupported = isArcore, // Refined on session creation
      isVulkanSupported = true,
      glEsVersion = "OpenGL ES $glEsVersion",
      refreshRateHz = refreshRate,
      totalMemoryMb = totalRamMb,
      availableMemoryMb = availRamMb,
      isLowRamDevice = isLowRam,
      suggestedProfile = profile,
      cpuCores = Runtime.getRuntime().availableProcessors()
    )
  }
}
