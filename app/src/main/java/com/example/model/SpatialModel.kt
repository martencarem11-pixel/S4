package com.example.model

import com.example.arcore.ExhibitSource
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Represents a 3D Mesh with geometry, materials, and bounding data for PBR rendering.
 */
data class SpatialMesh(
  val name: String,
  val vertexBuffer: FloatBuffer,
  val normalBuffer: FloatBuffer?,
  val uvBuffer: FloatBuffer?,
  val colorBuffer: FloatBuffer?,
  val indexBuffer: ShortBuffer,
  val indexCount: Int,
  val material: PbrMaterial = PbrMaterial()
)

/**
 * PBR (Physically Based Rendering) Material Properties
 */
data class PbrMaterial(
  val baseColorR: Float = 0.2f,
  val baseColorG: Float = 0.7f,
  val baseColorB: Float = 1.0f,
  val baseColorA: Float = 1.0f,
  val roughness: Float = 0.35f,
  val metallic: Float = 0.65f,
  val emissiveIntensity: Float = 0.0f,
  val emissiveR: Float = 0.0f,
  val emissiveG: Float = 0.8f,
  val emissiveB: Float = 1.0f,
  val isDoubleSided: Boolean = false,
  val isWireframe: Boolean = false,
  val isUnlit: Boolean = false
)

/**
 * High-level 3D Model entity containing meshes, metadata and transform state.
 */
data class SpatialModel(
  val id: String,
  val title: String,
  val description: String,
  val category: String,
  val vertexCount: Int,
  val triangleCount: Int,
  val isCustomLoaded: Boolean = false,
  val meshes: List<SpatialMesh> = emptyList(),
  val hasAnimations: Boolean = false,
  val animationDurationSec: Float = 0.0f
)

/**
 * Active application rendering modes
 */
enum class DisplayMode {
  MR,     // Mixed Reality - Stereoscopic Dual-Viewport + Spatial tracking
  AR,     // Augmented Reality - Passthrough camera + Plane & Image tracking & Anchoring
  OBJECT  // 3D Object inspection - Orbit, zoom, lighting & inspection
}

/**
 * AR Anchor representing a placed 3D model in real-world space.
 */
data class SpatialAnchor(
  val id: String,
  val posX: Float,
  val posY: Float,
  val posZ: Float,
  val rotY: Float,
  val scale: Float = 1.0f,
  val modelId: String,
  val modelTitle: String = "",
  val source: ExhibitSource = ExhibitSource.PLANE_TAP,
  val markerId: String? = null,
  val distanceToCameraMeters: Float = 0f,
  val timestamp: Long = System.currentTimeMillis()
)
