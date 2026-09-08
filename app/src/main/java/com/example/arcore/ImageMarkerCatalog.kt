package com.example.arcore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.model.SpatialModel
import com.example.parser.GltfAssetFactory

enum class ExhibitSource {
  PLANE_TAP,         // Placed manually on detected horizontal/vertical plane
  INSTANT_PLACEMENT, // Placed via Instant Placement before surface fully tracked
  DEPTH_HIT,         // Placed via Depth Point Cloud / Depth Map Hit
  IMAGE_MARKER       // Automatically spawned and anchored on recognized physical image marker
}

data class ExhibitMarker(
  val markerId: String,
  val modelId: String,
  val title: String,
  val category: String,
  val physicalWidthMeters: Float = 0.15f, // 15 cm default physical card width
  val description: String,
  val accentColorHex: Long = 0xFF38BDF8
)

/**
 * Catalog of AR target image markers mapped to 3D GLB exhibit models.
 * Generates high-entropy spatial visual marker bitmaps optimized for ARCore's
 * FAST/ORB keypoint feature detection.
 */
object ImageMarkerCatalog {

  val exhibits = listOf(
    ExhibitMarker(
      markerId = "marker_drone",
      modelId = "drone_v1",
      title = "Autonomous Drone X-1",
      category = "Robotics & Aerial",
      physicalWidthMeters = 0.15f,
      description = "Next-generation autonomous quad-rotor drone with lidar sensor pod and lightweight carbon-composite chassis.",
      accentColorHex = 0xFF38BDF8
    ),
    ExhibitMarker(
      markerId = "marker_core",
      modelId = "scifi_core",
      title = "Quantum Fusion Core",
      category = "Energy & Physics",
      physicalWidthMeters = 0.15f,
      description = "Tokamak-inspired magnetic confinement fusion reactor generating clean plasma energy.",
      accentColorHex = 0xFFF97316
    ),
    ExhibitMarker(
      markerId = "marker_mech",
      modelId = "mech_v1",
      title = "Cybernetic Mech Rig",
      category = "Exoskeleton & Heavy Industry",
      physicalWidthMeters = 0.15f,
      description = "Hydraulically powered industrial bipedal exoskeleton designed for extreme planetary environments.",
      accentColorHex = 0xFF22C55E
    ),
    ExhibitMarker(
      markerId = "marker_helmet",
      modelId = "astronaut_v1",
      title = "Deep Space EVA Helmet",
      category = "Aerospace & Exploration",
      physicalWidthMeters = 0.15f,
      description = "Gold-vapor deposition visor with integrated heads-up display and micro-meteorite thermal shielding.",
      accentColorHex = 0xFFEAB308
    )
  )

  fun findByMarkerId(markerId: String): ExhibitMarker? {
    return exhibits.firstOrNull { it.markerId == markerId }
  }

  fun findByModelId(modelId: String): ExhibitMarker? {
    return exhibits.firstOrNull { it.modelId == modelId }
  }

  /**
   * Generates a high-entropy, high-contrast 512x512 Bitmap marker card
   * with unique ArUco-like spatial geometry, high-frequency corners, and binary patterns.
   * ARCore requires high visual gradient entropy (>50 feature points) for reliable 6DoF tracking.
   */
  fun generateMarkerBitmap(marker: ExhibitMarker): Bitmap {
    val size = 512
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 1. High contrast dark background with thick outer border
    paint.color = Color.BLACK
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 24f
    canvas.drawRect(12f, 12f, size - 12f, size - 12f, paint)

    // 2. Corner alignment squares (ArUco / QR style) for robust homography estimation
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    val cornerSize = 90f
    // Top-Left
    canvas.drawRect(30f, 30f, 30f + cornerSize, 30f + cornerSize, paint)
    // Top-Right
    canvas.drawRect(size - 30f - cornerSize, 30f, size - 30f, 30f + cornerSize, paint)
    // Bottom-Left
    canvas.drawRect(30f, size - 30f - cornerSize, 30f + cornerSize, size - 30f, paint)
    // Bottom-Right
    canvas.drawRect(size - 30f - cornerSize, size - 30f - cornerSize, size - 30f, size - 30f, paint)

    // Inner black corner cores
    paint.color = Color.BLACK
    val innerCore = 40f
    val offset = (cornerSize - innerCore) / 2f
    canvas.drawRect(30f + offset, 30f + offset, 30f + offset + innerCore, 30f + offset + innerCore, paint)
    canvas.drawRect(size - 30f - cornerSize + offset, 30f + offset, size - 30f - cornerSize + offset + innerCore, 30f + offset + innerCore, paint)
    canvas.drawRect(30f + offset, size - 30f - cornerSize + offset, 30f + offset + innerCore, size - 30f - cornerSize + offset + innerCore, paint)
    canvas.drawRect(size - 30f - cornerSize + offset, size - 30f - cornerSize + offset, size - 30f - cornerSize + offset + innerCore, size - 30f - cornerSize + offset + innerCore, paint)

    // 3. Center Unique Geometric Identification Pattern
    paint.color = Color.WHITE
    val center = size / 2f
    when (marker.markerId) {
      "marker_drone" -> {
        // Drone quad-rotor geometric cross pattern
        paint.strokeWidth = 20f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(150f, 150f, 362f, 362f, paint)
        canvas.drawLine(150f, 362f, 362f, 150f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, 45f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(center, center, 20f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(150f, 150f, 25f, paint)
        canvas.drawCircle(362f, 150f, 25f, paint)
        canvas.drawCircle(150f, 362f, 25f, paint)
        canvas.drawCircle(362f, 362f, 25f, paint)
      }
      "marker_core" -> {
        // Quantum fusion core concentric segmented rings
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        canvas.drawCircle(center, center, 90f, paint)
        paint.strokeWidth = 14f
        canvas.drawCircle(center, center, 55f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, 26f, paint)
        // High-frequency radial tick marks
        for (i in 0 until 8) {
          val angle = Math.toRadians((i * 45.0))
          val x1 = center + (95f * Math.cos(angle)).toFloat()
          val y1 = center + (95f * Math.sin(angle)).toFloat()
          val x2 = center + (125f * Math.cos(angle)).toFloat()
          val y2 = center + (125f * Math.sin(angle)).toFloat()
          paint.strokeWidth = 10f
          canvas.drawLine(x1, y1, x2, y2, paint)
        }
      }
      "marker_mech" -> {
        // Hexagonal cybernetic lattice
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        val path = Path()
        for (i in 0 until 6) {
          val angle = Math.toRadians((i * 60.0 - 30.0))
          val x = center + (110f * Math.cos(angle)).toFloat()
          val y = center + (110f * Math.sin(angle)).toFloat()
          if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(center - 35f, center - 35f, center + 35f, center + 35f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(center, center, 18f, paint)
      }
      "marker_helmet" -> {
        // Astronaut visor elliptical arch pattern
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 20f
        val visorRect = RectF(center - 100f, center - 70f, center + 100f, center + 70f)
        canvas.drawRoundRect(visorRect, 40f, 40f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, 32f, paint)
        paint.color = Color.BLACK
        canvas.drawRect(center - 15f, center - 15f, center + 15f, center + 15f, paint)
      }
    }

    // 4. Marker ID text label encoded along bottom edge
    paint.color = Color.WHITE
    paint.style = Paint.Style.FILL
    paint.textSize = 22f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText("EXHIBIT :: ${marker.title.uppercase()}", center, size - 45f, paint)

    return bitmap
  }
}
