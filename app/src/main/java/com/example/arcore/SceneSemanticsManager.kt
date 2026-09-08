package com.example.arcore

import android.media.Image
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import java.nio.ByteBuffer

/**
 * Result of querying semantics at a specific normalized screen coordinate.
 */
data class SemanticQueryResult(
  val label: String,
  val confidencePercent: Int,
  val isOutdoor: Boolean,
  val xNormalized: Float,
  val yNormalized: Float
)

/**
 * Higher-density semantic telemetry with multi-region spatial breakdown.
 */
data class SemanticsTelemetry(
  val isSemanticModeSupported: Boolean = false,
  val isEnabled: Boolean = false,
  val isGpuSemanticRenderingActive: Boolean = false, // Analyzed on CPU frame buffers; GPU semantic rendering only reported when bound to shader pipeline
  val dominantLabel: String = "INITIALIZING",
  val dominantConfidencePercent: Int = 0,
  val isOutdoorScene: Boolean = false,
  val detectedClassesCount: Int = 0,
  val centerZoneLabel: String = "UNKNOWN",
  val floorZoneLabel: String = "UNKNOWN",
  val skyZoneLabel: String = "UNKNOWN",
  val classDistribution: Map<String, Float> = emptyMap()
)

/**
 * Production-Grade ARCore Scene Semantics & Environmental Understanding Manager.
 * Features:
 * 1. Complete semantic query architecture (single-point hit query, multi-zone classification).
 * 2. High-density semantic evaluation with confidence weighting across spatial regions.
 * 3. Real class distribution histogram and regional environment zoning (Sky, Interaction, Floor).
 * 4. Device-aware runtime support detection.
 */
class SceneSemanticsManager {

  companion object {
    private const val TAG = "SceneSemanticsManager"
    private const val GRID_STEPS = 24 // 24x24 = 576 samples for high-density analysis
  }

  var telemetry: SemanticsTelemetry = SemanticsTelemetry()
    private set

  private val viewCoordScratch = FloatArray(2)
  private val semanticCoordScratch = FloatArray(2)

  /**
   * Configures Scene Semantics mode in ARCore Session configuration.
   */
  fun configureSemanticsMode(session: Session, config: Config): Boolean {
    return try {
      if (session.isSemanticModeSupported(Config.SemanticMode.ENABLED)) {
        config.semanticMode = Config.SemanticMode.ENABLED
        telemetry = telemetry.copy(isSemanticModeSupported = true, isEnabled = true, dominantLabel = "INITIALIZING")
        Log.i(TAG, "ARCore Scene Semantics enabled.")
        true
      } else {
        config.semanticMode = Config.SemanticMode.DISABLED
        telemetry = telemetry.copy(isSemanticModeSupported = false, isEnabled = false, dominantLabel = "UNSUPPORTED")
        Log.i(TAG, "ARCore Scene Semantics unsupported on this device.")
        false
      }
    } catch (e: Exception) {
      Log.w(TAG, "Semantics configuration skipped: ${e.message}")
      false
    }
  }

  /**
   * High-density frame semantic processing: evaluates full spatial distribution and regional zones.
   */
  fun processFrameSemantics(frame: Frame) {
    if (!telemetry.isEnabled) return

    var semanticImage: Image? = null
    var confidenceImage: Image? = null

    try {
      semanticImage = frame.acquireSemanticImage()
      confidenceImage = try {
        frame.acquireSemanticConfidenceImage()
      } catch (_: Exception) { null }

      val width = semanticImage.width
      val height = semanticImage.height
      val planes = semanticImage.planes
      if (planes.isEmpty()) return

      val buffer: ByteBuffer = planes[0].buffer
      val pixelStride = planes[0].pixelStride
      val rowStride = planes[0].rowStride

      val confBuffer: ByteBuffer? = confidenceImage?.planes?.getOrNull(0)?.buffer
      val confPixelStride = confidenceImage?.planes?.getOrNull(0)?.pixelStride ?: 1
      val confRowStride = confidenceImage?.planes?.getOrNull(0)?.rowStride ?: 1

      val stepX = maxOf(width / GRID_STEPS, 1)
      val stepY = maxOf(height / GRID_STEPS, 1)

      val labelWeightedCounts = mutableMapOf<String, Float>()
      val labelRawCounts = mutableMapOf<String, Int>()

      var centerLabel = "UNKNOWN"
      var floorLabel = "UNKNOWN"
      var skyLabel = "UNKNOWN"

      var totalConfidenceWeighted = 0f
      var totalSamples = 0

      for (y in 0 until height step stepY) {
        val rowStart = y * rowStride
        val confRowStart = y * confRowStride
        val normY = y.toFloat() / height

        for (x in 0 until width step stepX) {
          val byteIndex = rowStart + x * pixelStride
          if (byteIndex < buffer.limit()) {
            val labelOrdinal = buffer.get(byteIndex).toInt() and 0xFF
            val labelName = getSemanticLabelName(labelOrdinal)

            // Confidence weight [0..1]
            val confidenceWeight = if (confBuffer != null) {
              val confIdx = confRowStart + x * confPixelStride
              if (confIdx < confBuffer.limit()) {
                (confBuffer.get(confIdx).toInt() and 0xFF) / 255f
              } else { 1.0f }
            } else {
              1.0f
            }

            labelWeightedCounts[labelName] = (labelWeightedCounts[labelName] ?: 0f) + confidenceWeight
            labelRawCounts[labelName] = (labelRawCounts[labelName] ?: 0) + 1
            totalConfidenceWeighted += confidenceWeight
            totalSamples++

            val normX = x.toFloat() / width
            // Assign zone samples
            if (normX in 0.4f..0.6f && normY in 0.4f..0.6f) {
              centerLabel = labelName
            }
            if (normY > 0.8f && normX in 0.4f..0.6f) {
              floorLabel = labelName
            }
            if (normY < 0.2f && normX in 0.4f..0.6f) {
              skyLabel = labelName
            }
          }
        }
      }

      val dominantEntry = labelWeightedCounts.maxByOrNull { it.value }
      if (dominantEntry != null && totalSamples > 0) {
        val dominantLabel = dominantEntry.key
        val dominantConfidencePct = if (totalConfidenceWeighted > 0f) {
          ((dominantEntry.value / totalConfidenceWeighted) * 100).toInt().coerceIn(0, 100)
        } else {
          0
        }

        val isOutdoor = dominantLabel in listOf("SKY", "BUILDING", "ROAD", "SIDEWALK", "TERRAIN", "TREE")

        // Compute class distribution map (percentages)
        val distribution = labelWeightedCounts.mapValues { (_, count) ->
          if (totalConfidenceWeighted > 0f) (count / totalConfidenceWeighted) else 0f
        }

        telemetry = telemetry.copy(
          dominantLabel = dominantLabel,
          dominantConfidencePercent = dominantConfidencePct,
          isOutdoorScene = isOutdoor,
          detectedClassesCount = labelRawCounts.size,
          centerZoneLabel = centerLabel,
          floorZoneLabel = floorLabel,
          skyZoneLabel = skyLabel,
          classDistribution = distribution
        )
      }
    } catch (e: Exception) {
      // Frame might not yet have semantic image ready
    } finally {
      semanticImage?.close()
      confidenceImage?.close()
    }
  }

  /**
   * Queries semantic label and confidence at normalized viewport coordinates [0..1].
   */
  fun querySemanticAtViewport(frame: Frame, normX: Float, normY: Float): SemanticQueryResult {
    var image: Image? = null
    var confImage: Image? = null
    return try {
      image = frame.acquireSemanticImage()
      confImage = try { frame.acquireSemanticConfidenceImage() } catch (_: Exception) { null }

      viewCoordScratch[0] = normX.coerceIn(0f, 1f)
      viewCoordScratch[1] = normY.coerceIn(0f, 1f)
      frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewCoordScratch,
        Coordinates2d.IMAGE_NORMALIZED,
        semanticCoordScratch
      )

      val px = (semanticCoordScratch[0].coerceIn(0f, 1f) * (image.width - 1)).toInt()
      val py = (semanticCoordScratch[1].coerceIn(0f, 1f) * (image.height - 1)).toInt()

      val plane = image.planes[0]
      val idx = py * plane.rowStride + px * plane.pixelStride
      val ordinal = plane.buffer.get(idx).toInt() and 0xFF
      val label = getSemanticLabelName(ordinal)

      val confidencePct = confImage?.let { cImg ->
        try {
          val cPlane = cImg.planes[0]
          val cIdx = py * cPlane.rowStride + px * cPlane.pixelStride
          ((cPlane.buffer.get(cIdx).toInt() and 0xFF) * 100) / 255
        } catch (_: Exception) { 0 }
      } ?: 0

      // Strictly reject low-confidence samples (< 60%) to prevent false classifications
      val effectiveLabel = if (confidencePct < 60) "LOW_CONFIDENCE" else label
      val isOutdoor = effectiveLabel in listOf("SKY", "BUILDING", "ROAD", "SIDEWALK", "TERRAIN", "TREE")

      SemanticQueryResult(
        label = effectiveLabel,
        confidencePercent = confidencePct,
        isOutdoor = isOutdoor,
        xNormalized = normX,
        yNormalized = normY
      )
    } catch (_: Exception) {
      SemanticQueryResult("UNKNOWN", 0, false, normX, normY)
    } finally {
      image?.close()
      confImage?.close()
    }
  }

  fun getSemanticLabelAt(frame: Frame, normX: Float, normY: Float): String {
    val res = querySemanticAtViewport(frame, normX, normY)
    return if (res.confidencePercent >= 60) res.label else "LOW_CONFIDENCE"
  }

  /**
   * Converts ARCore SemanticLabel int value to human-readable string name.
   */
  fun getSemanticLabelName(ordinal: Int): String {
    return try {
      val values = SemanticLabel.values()
      if (ordinal in values.indices) {
        values[ordinal].name
      } else {
        "UNLABELED"
      }
    } catch (_: Throwable) {
      when (ordinal) {
        0 -> "UNLABELED"
        1 -> "SKY"
        2 -> "BUILDING"
        3 -> "TREE"
        4 -> "ROAD"
        5 -> "SIDEWALK"
        6 -> "TERRAIN"
        7 -> "STRUCTURE"
        8 -> "OBJECT"
        9 -> "VEHICLE"
        10 -> "PERSON"
        11 -> "WATER"
        else -> "SURFACE"
      }
    }
  }
}
