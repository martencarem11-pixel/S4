package com.example.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

enum class ThermalQualityLevel(
  val resolutionScale: Float,
  val msaaSamples: Int,
  val enableFxaa: Boolean,
  val isThrottled: Boolean
) {
  HIGH(resolutionScale = 1.0f, msaaSamples = 2, enableFxaa = true, isThrottled = false),
  MEDIUM(resolutionScale = 0.9f, msaaSamples = 1, enableFxaa = true, isThrottled = true),
  LOW(resolutionScale = 0.75f, msaaSamples = 1, enableFxaa = false, isThrottled = true),
  EMERGENCY(resolutionScale = 0.5f, msaaSamples = 1, enableFxaa = false, isThrottled = true)
}

/**
 * Monitors device thermal status via PowerManager and dynamically adapts
 * rendering quality and resolution to safeguard hardware from thermal throttling.
 */
class ThermalGuardManager(
  private val context: Context,
  private val onThermalStatusChanged: (ThermalQualityLevel, String) -> Unit
) {
  companion object {
    private const val TAG = "ThermalGuard"
  }

  private val powerManager: PowerManager? =
    context.getSystemService(Context.POWER_SERVICE) as? PowerManager

  private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
  private var isMonitoring = false

  fun startMonitoring() {
    if (isMonitoring) return
    isMonitoring = true

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
      try {
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
          val (level, statusStr) = mapThermalStatus(status)
          onThermalStatusChanged(level, statusStr)
        }
        thermalListener = listener
        powerManager.addThermalStatusListener(listener)

        val currentStatus = powerManager.currentThermalStatus
        val (level, statusStr) = mapThermalStatus(currentStatus)
        onThermalStatusChanged(level, statusStr)
      } catch (e: Exception) {
        Log.w(TAG, "Unable to register thermal status listener: ${e.message}")
        onThermalStatusChanged(ThermalQualityLevel.HIGH, "Nominal (Safe)")
      }
    } else {
      onThermalStatusChanged(ThermalQualityLevel.HIGH, "Nominal (Safe)")
    }
  }

  fun stopMonitoring() {
    if (!isMonitoring) return
    isMonitoring = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
      try {
        powerManager.removeThermalStatusListener(thermalListener!!)
      } catch (e: Exception) {
        Log.w(TAG, "Error removing thermal status listener: ${e.message}")
      }
      thermalListener = null
    }
  }

  private fun mapThermalStatus(status: Int): Pair<ThermalQualityLevel, String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      when (status) {
        PowerManager.THERMAL_STATUS_NONE -> Pair(ThermalQualityLevel.HIGH, "Nominal (Safe)")
        PowerManager.THERMAL_STATUS_LIGHT -> Pair(ThermalQualityLevel.MEDIUM, "Light Throttling")
        PowerManager.THERMAL_STATUS_MODERATE -> Pair(ThermalQualityLevel.MEDIUM, "Moderate Throttling")
        PowerManager.THERMAL_STATUS_SEVERE -> Pair(ThermalQualityLevel.LOW, "Severe Throttling")
        PowerManager.THERMAL_STATUS_CRITICAL -> Pair(ThermalQualityLevel.EMERGENCY, "Critical Throttling")
        PowerManager.THERMAL_STATUS_EMERGENCY -> Pair(ThermalQualityLevel.EMERGENCY, "Emergency Throttling")
        PowerManager.THERMAL_STATUS_SHUTDOWN -> Pair(ThermalQualityLevel.EMERGENCY, "Device Shutdown Imminent")
        else -> Pair(ThermalQualityLevel.HIGH, "Nominal (Safe)")
      }
    } else {
      Pair(ThermalQualityLevel.HIGH, "Nominal (Safe)")
    }
  }
}
