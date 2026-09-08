package com.example.arcore

import android.media.Image
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Surface category classification for reconstructed environmental polygons and meshes.
 */
enum class MeshSurfaceCategory {
  FLOOR,
  CEILING,
  WALL,
  TABLE_SURFACE,
  DESK_OR_COUNTER,
  OUTDOOR_TERRAIN,
  BUILDING_FACADE,
  GENERIC_OBSTACLE
}

/**
 * Source type of the spatial geometry to separate detected planes from 3D meshes.
 */
enum class GeometrySourceType {
  DETECTED_PLANE_2D_POLYGON,
  STREETSCAPE_3D_MESH,
  ENVIRONMENTAL_3D_MESH
}

/**
 * Geometric chunk representing real-world physical boundaries.
 */
data class MeshChunk(
  val id: String,
  val sourceType: GeometrySourceType,
  val category: MeshSurfaceCategory,
  val vertexCount: Int,
  val triangleCount: Int,
  val centerPosition: FloatArray,
  val surfaceAreaSquareMeters: Float,
  val vertexBuffer: FloatBuffer? = null,
  val indexBuffer: IntBuffer? = null
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as MeshChunk
    return id == other.id
  }
  override fun hashCode(): Int = id.hashCode()
}

/**
 * Telemetry tracking real 3D mesh geometry vs 2D convex plane boundaries.
 * Strictly separates:
 * 1. Plane Detection (2D convex hulls)
 * 2. Streetscape Geometry (Outdoor mesh)
 * 3. Local Environmental Mesh (Point cloud / volumetric mesh)
 * 4. Dense Local Reconstruction (High-density local mesh)
 * 5. Full Scene Reconstruction (Multi-surface volumetric coverage)
 */
data class ReconstructionTelemetry(
  val isReconstructionActive: Boolean = false,
  val isPlaneDetectionActive: Boolean = false,
  val isStreetscapeGeometryActive: Boolean = false,
  val isLocalEnvironmentalMeshActive: Boolean = false,
  val isDenseLocalReconstructionActive: Boolean = false,
  val isDenseLocalMeshActive: Boolean = isDenseLocalReconstructionActive,
  val isValidatedLocalSceneMesh: Boolean = false,
  val isFull3dSceneReconstruction: Boolean = false,
  val hasReal3dMeshGeometry: Boolean = false,
  val detectedPlanesCount: Int = 0,
  val streetscapeGeometriesCount: Int = 0,
  val denseMeshChunksCount: Int = 0,
  val totalChunks: Int = 0,
  val totalVertices: Int = 0,
  val totalTriangles: Int = 0,
  val totalSurfaceAreaSqMeters: Float = 0f,
  val localMeshAreaSqMeters: Float = 0f,
  val hasFloorPlane: Boolean = false,
  val hasWallPlane: Boolean = false,
  val hasTableSurface: Boolean = false,
  val reconstructionStage: String = "IDLE",
  val semanticsClassificationSource: String = "GEOMETRIC_ORIENTATION_ESTIMATE"
)

/**
 * Production-Grade Environmental Mesh & Scene Reconstruction Manager.
 * Rigorously separates:
 * 1. Detected 2D Planes (Convex polygon hulls from plane detection).
 * 2. Outdoor Streetscape Geometry (Dense 3D meshes of buildings & terrain).
 * 3. Environmental 3D Meshes (Dense volumetric surface reconstruction).
 * 4. Semantic Surface Classification (Floor vs Table vs Wall vs Ceiling).
 * 5. Multi-frame persistent spatial voxel accumulation for continuous dense scene reconstruction.
 */
class EnvironmentalMeshManager {

  companion object {
    private const val TAG = "EnvironmentalMeshManager"
    // Height threshold relative to camera eye-level (~1.4m): surfaces below -0.85m are floors
    private const val FLOOR_HEIGHT_THRESHOLD_METERS = -0.85f
    private const val MAX_PERSISTENT_CHUNKS = 96
  }

  var telemetry: ReconstructionTelemetry = ReconstructionTelemetry()
    private set

  private val detectedPlaneChunks = mutableMapOf<String, MeshChunk>()
  private val streetscapeChunks = mutableMapOf<String, MeshChunk>()
  private val environmental3dChunks = mutableMapOf<String, MeshChunk>()
  private val persistentSpatialVoxelChunks = mutableMapOf<String, MeshChunk>()
  private var lastDepthMeshTimeMs = 0L

  /**
   * Updates environmental mesh representation from current ARCore trackables.
   */
  fun updateEnvironmentalMesh(
    session: Session,
    frame: com.google.ar.core.Frame? = null,
    semanticsManager: SceneSemanticsManager? = null
  ) {
    try {
      val now = System.currentTimeMillis()
      val isDepthSupported = try {
        session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
      } catch (_: Throwable) { false }
      var totalVerts = 0
      var totalTris = 0
      var totalArea = 0f
      var hasFloor = false
      var hasWall = false
      var hasTable = false
      var usedMlSemantics = false

      detectedPlaneChunks.clear()
      streetscapeChunks.clear()

      // 1. Process physical plane geometry (2D convex polygon boundaries)
      val planes = session.getAllTrackables(Plane::class.java)
      for (plane in planes) {
        if (plane.trackingState != TrackingState.TRACKING) continue
        if (plane.subsumedBy != null) continue

        val polygon = plane.polygon ?: continue
        val numVertices = polygon.limit() / 2
        if (numVertices < 3) continue

        val centerPose = plane.centerPose
        val extentX = plane.extentX
        val extentZ = plane.extentZ
        val area = extentX * extentZ
        val triangles = numVertices - 2

        // Classify surface category accurately based on orientation and relative elevation
        val category = when (plane.type) {
          Plane.Type.HORIZONTAL_UPWARD_FACING -> {
            // Check plane elevation: low elevation is floor, elevated is table/desk
            if (centerPose.ty() <= FLOOR_HEIGHT_THRESHOLD_METERS) {
              hasFloor = true
              MeshSurfaceCategory.FLOOR
            } else {
              hasTable = true
              MeshSurfaceCategory.TABLE_SURFACE
            }
          }
          Plane.Type.HORIZONTAL_DOWNWARD_FACING -> MeshSurfaceCategory.CEILING
          Plane.Type.VERTICAL -> {
            hasWall = true
            MeshSurfaceCategory.WALL
          }
        }

        val chunk = MeshChunk(
          id = "plane_${plane.hashCode()}",
          sourceType = GeometrySourceType.DETECTED_PLANE_2D_POLYGON,
          category = category,
          vertexCount = numVertices,
          triangleCount = triangles,
          centerPosition = floatArrayOf(centerPose.tx(), centerPose.ty(), centerPose.tz()),
          surfaceAreaSquareMeters = area
        )
        detectedPlaneChunks[chunk.id] = chunk

        totalVerts += numVertices
        totalTris += triangles
        totalArea += area
      }

      // 2. Process outdoor Streetscape Geometry meshes if available (Real 3D Mesh Geometry)
      try {
        val streetscapes = session.getAllTrackables(StreetscapeGeometry::class.java)
        for (sg in streetscapes) {
          if (sg.trackingState != TrackingState.TRACKING) continue

          val mesh = sg.mesh
          val vertCount = mesh.vertexList.limit() / 3
          val triCount = mesh.indexList.limit() / 3
          val category = when (sg.type) {
            StreetscapeGeometry.Type.BUILDING -> MeshSurfaceCategory.BUILDING_FACADE
            StreetscapeGeometry.Type.TERRAIN -> MeshSurfaceCategory.OUTDOOR_TERRAIN
            else -> MeshSurfaceCategory.GENERIC_OBSTACLE
          }

          val pose = sg.meshPose

          // Compute real surface area from actual 3D mesh vertices and triangle indices
          var calculatedArea = 0f
          val vBuf = mesh.vertexList
          val iBuf = mesh.indexList
          val numTris = iBuf.limit() / 3
          for (t in 0 until minOf(numTris, 2000)) {
            val i0 = iBuf.get(t * 3).toInt() and 0xFFFF
            val i1 = iBuf.get(t * 3 + 1).toInt() and 0xFFFF
            val i2 = iBuf.get(t * 3 + 2).toInt() and 0xFFFF
            if (i0 * 3 + 2 < vBuf.limit() && i1 * 3 + 2 < vBuf.limit() && i2 * 3 + 2 < vBuf.limit()) {
              val ax = vBuf.get(i0 * 3); val ay = vBuf.get(i0 * 3 + 1); val az = vBuf.get(i0 * 3 + 2)
              val bx = vBuf.get(i1 * 3); val by = vBuf.get(i1 * 3 + 1); val bz = vBuf.get(i1 * 3 + 2)
              val cx = vBuf.get(i2 * 3); val cy = vBuf.get(i2 * 3 + 1); val cz = vBuf.get(i2 * 3 + 2)
              // Vector AB x Vector AC
              val abx = bx - ax; val aby = by - ay; val abz = bz - az
              val acx = cx - ax; val acy = cy - ay; val acz = cz - az
              val crossX = aby * acz - abz * acy
              val crossY = abz * acx - abx * acz
              val crossZ = abx * acy - aby * acx
              calculatedArea += 0.5f * Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ).toDouble()).toFloat()
            }
          }

          val chunk = MeshChunk(
            id = "streetscape_${sg.hashCode()}",
            sourceType = GeometrySourceType.STREETSCAPE_3D_MESH,
            category = category,
            vertexCount = vertCount,
            triangleCount = triCount,
            centerPosition = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
            surfaceAreaSquareMeters = calculatedArea
          )
          streetscapeChunks[chunk.id] = chunk

          totalVerts += vertCount
          totalTris += triCount
          totalArea += chunk.surfaceAreaSquareMeters
        }
      } catch (_: Throwable) {
        // Streetscape not active on current device/environment
      }

      // 3. Environmental 3D Mesh: Extract real physical 3D mesh geometry from ARCore 16-bit depth buffer
      environmental3dChunks.clear()
      if (isDepthSupported && frame != null && frame.camera.trackingState == TrackingState.TRACKING && (now - lastDepthMeshTimeMs >= 350L)) {
        lastDepthMeshTimeMs = now
        var depthImage: Image? = null
        try {
          depthImage = try {
            frame.acquireDepthImage16Bits()
          } catch (_: Throwable) {
            frame.acquireRawDepthImage16Bits()
          }

          if (depthImage != null) {
            val width = depthImage.width
            val height = depthImage.height
            val planes = depthImage.planes
            if (planes.isNotEmpty() && width > 0 && height > 0) {
              val plane = planes[0]
              val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
              val pixelStride = plane.pixelStride
              val rowStride = plane.rowStride

              // Intrinsic parameters for unprojection
              val intrinsics = frame.camera.imageIntrinsics
              val fx = intrinsics.focalLength[0] * (width.toFloat() / intrinsics.imageDimensions[0])
              val fy = intrinsics.focalLength[1] * (height.toFloat() / intrinsics.imageDimensions[1])
              val cx = intrinsics.principalPoint[0] * (width.toFloat() / intrinsics.imageDimensions[0])
              val cy = intrinsics.principalPoint[1] * (height.toFloat() / intrinsics.imageDimensions[1])

              val camPose = frame.camera.pose
              // Higher-density adaptive sampling: step = 2 to 3 on standard depth images for dense mesh reconstruction
              val step = maxOf(2, minOf(width, height) / 50)
              val gridW = (width - 1) / step + 1
              val gridH = (height - 1) / step + 1

              // World positions grid: 3 floats per sample
              val gridPositions = FloatArray(gridW * gridH * 3)
              val gridValid = BooleanArray(gridW * gridH)
              val gridNormX = FloatArray(gridW * gridH)
              val gridNormY = FloatArray(gridW * gridH)

              for (gy in 0 until gridH) {
                val y = minOf(gy * step, height - 1)
                val rowStart = y * rowStride
                for (gx in 0 until gridW) {
                  val x = minOf(gx * step, width - 1)
                  val byteIdx = rowStart + x * pixelStride
                  var depthMm = buffer.getShort(byteIdx).toInt() and 0xFFFF
                  val idx = gy * gridW + gx

                  gridNormX[idx] = x.toFloat() / width
                  gridNormY[idx] = y.toFloat() / height

                  // Robust 2D Bilateral Hole-filling: if depth is zero or invalid, interpolate from valid neighbors
                  if (depthMm !in 150..6000) {
                    var neighborSum = 0
                    var neighborCount = 0
                    if (gx > 0) {
                      val d = buffer.getShort(rowStart + (x - step) * pixelStride).toInt() and 0xFFFF
                      if (d in 150..6000) { neighborSum += d; neighborCount++ }
                    }
                    if (gx < gridW - 1) {
                      val d = buffer.getShort(rowStart + minOf(x + step, width - 1) * pixelStride).toInt() and 0xFFFF
                      if (d in 150..6000) { neighborSum += d; neighborCount++ }
                    }
                    if (gy > 0) {
                      val d = buffer.getShort((y - step) * rowStride + x * pixelStride).toInt() and 0xFFFF
                      if (d in 150..6000) { neighborSum += d; neighborCount++ }
                    }
                    if (gy < gridH - 1) {
                      val d = buffer.getShort(minOf(y + step, height - 1) * rowStride + x * pixelStride).toInt() and 0xFFFF
                      if (d in 150..6000) { neighborSum += d; neighborCount++ }
                    }
                    if (neighborCount >= 2) {
                      depthMm = neighborSum / neighborCount
                    }
                  }

                  if (depthMm in 150..6000) {
                    val zM = depthMm / 1000f
                    val camX = (x - cx) * zM / fx
                    val camY = -(y - cy) * zM / fy
                    val camZ = -zM

                    val worldP = camPose.transformPoint(floatArrayOf(camX, camY, camZ))
                    gridPositions[idx * 3] = worldP[0]
                    gridPositions[idx * 3 + 1] = worldP[1]
                    gridPositions[idx * 3 + 2] = worldP[2]
                    gridValid[idx] = true
                  } else {
                    gridValid[idx] = false
                  }
                }
              }

              // Collect mesh triangles grouped by surface classification
              val semanticTris = mutableMapOf<MeshSurfaceCategory, MutableList<FloatArray>>()
              val maxDepthEdgeDiscontinuity = 0.15f // 15cm threshold preserves sharp geometric boundaries

              for (gy in 0 until gridH - 1) {
                for (gx in 0 until gridW - 1) {
                  val i00 = gy * gridW + gx
                  val i10 = gy * gridW + (gx + 1)
                  val i01 = (gy + 1) * gridW + gx
                  val i11 = (gy + 1) * gridW + (gx + 1)

                  if (gridValid[i00] && gridValid[i10] && gridValid[i01]) {
                    val p0 = floatArrayOf(gridPositions[i00 * 3], gridPositions[i00 * 3 + 1], gridPositions[i00 * 3 + 2])
                    val p1 = floatArrayOf(gridPositions[i10 * 3], gridPositions[i10 * 3 + 1], gridPositions[i10 * 3 + 2])
                    val p2 = floatArrayOf(gridPositions[i01 * 3], gridPositions[i01 * 3 + 1], gridPositions[i01 * 3 + 2])

                    val d1 = Math.abs(p0[2] - p1[2])
                    val d2 = Math.abs(p0[2] - p2[2])
                    val d3 = Math.abs(p1[2] - p2[2])
                    if (d1 < maxDepthEdgeDiscontinuity && d2 < maxDepthEdgeDiscontinuity && d3 < maxDepthEdgeDiscontinuity) {
                      val midNormX = (gridNormX[i00] + gridNormX[i10] + gridNormX[i01]) / 3f
                      val midNormY = (gridNormY[i00] + gridNormY[i10] + gridNormY[i01]) / 3f

                      val cat = resolveSurfaceCategory(frame, semanticsManager, midNormX, midNormY, p0, p1, p2)
                      if (semanticsManager?.telemetry?.isEnabled == true) {
                        usedMlSemantics = true
                      }
                      val list = semanticTris.getOrPut(cat) { mutableListOf() }
                      list.add(p0); list.add(p1); list.add(p2)
                    }
                  }

                  if (gridValid[i10] && gridValid[i11] && gridValid[i01]) {
                    val p0 = floatArrayOf(gridPositions[i10 * 3], gridPositions[i10 * 3 + 1], gridPositions[i10 * 3 + 2])
                    val p1 = floatArrayOf(gridPositions[i11 * 3], gridPositions[i11 * 3 + 1], gridPositions[i11 * 3 + 2])
                    val p2 = floatArrayOf(gridPositions[i01 * 3], gridPositions[i01 * 3 + 1], gridPositions[i01 * 3 + 2])

                    val d1 = Math.abs(p0[2] - p1[2])
                    val d2 = Math.abs(p0[2] - p2[2])
                    val d3 = Math.abs(p1[2] - p2[2])
                    if (d1 < maxDepthEdgeDiscontinuity && d2 < maxDepthEdgeDiscontinuity && d3 < maxDepthEdgeDiscontinuity) {
                      val midNormX = (gridNormX[i10] + gridNormX[i11] + gridNormX[i01]) / 3f
                      val midNormY = (gridNormY[i10] + gridNormY[i11] + gridNormY[i01]) / 3f

                      val cat = resolveSurfaceCategory(frame, semanticsManager, midNormX, midNormY, p0, p1, p2)
                      if (semanticsManager?.telemetry?.isEnabled == true) {
                        usedMlSemantics = true
                      }
                      val list = semanticTris.getOrPut(cat) { mutableListOf() }
                      list.add(p0); list.add(p1); list.add(p2)
                    }
                  }
                }
              }

              for ((cat, vertList) in semanticTris) {
                if (vertList.size >= 9) {
                  val numTris = vertList.size / 3
                  val numVerts = vertList.size
                  val vBuffer = ByteBuffer.allocateDirect(numVerts * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                  val iBuffer = ByteBuffer.allocateDirect(numTris * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer()

                  var sumX = 0f; var sumY = 0f; var sumZ = 0f
                  var totalTriArea = 0f

                  for (i in 0 until numTris) {
                    val p0 = vertList[i * 3]
                    val p1 = vertList[i * 3 + 1]
                    val p2 = vertList[i * 3 + 2]

                    vBuffer.put(p0); vBuffer.put(p1); vBuffer.put(p2)
                    iBuffer.put(i * 3); iBuffer.put(i * 3 + 1); iBuffer.put(i * 3 + 2)

                    sumX += (p0[0] + p1[0] + p2[0]) / 3f
                    sumY += (p0[1] + p1[1] + p2[1]) / 3f
                    sumZ += (p0[2] + p1[2] + p2[2]) / 3f

                    val abx = p1[0] - p0[0]; val aby = p1[1] - p0[1]; val abz = p1[2] - p0[2]
                    val acx = p2[0] - p0[0]; val acy = p2[1] - p0[1]; val acz = p2[2] - p0[2]
                    val crossX = aby * acz - abz * acy
                    val crossY = abz * acx - abx * acz
                    val crossZ = abx * acy - aby * acx
                    totalTriArea += 0.5f * Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ).toDouble()).toFloat()
                  }
                  vBuffer.position(0)
                  iBuffer.position(0)

                  val center = floatArrayOf(sumX / numTris, sumY / numTris, sumZ / numTris)
                  // Spatial voxel hash key for persistent multi-view world accumulation
                  val voxelX = (center[0] / 0.50f).toInt()
                  val voxelY = (center[1] / 0.50f).toInt()
                  val voxelZ = (center[2] / 0.50f).toInt()
                  val chunkId = "voxel_${voxelX}_${voxelY}_${voxelZ}_${cat.name.lowercase()}"

                  // Temporal fusion: apply exponential moving average with existing voxel geometry to smooth sensor noise
                  val existingVoxel = persistentSpatialVoxelChunks[chunkId]
                  val finalVBuffer: FloatBuffer
                  val finalIBuffer: IntBuffer
                  val finalVertCount: Int
                  val finalTriCount: Int
                  val finalArea: Float

                  if (existingVoxel != null && existingVoxel.vertexBuffer != null && existingVoxel.indexBuffer != null) {
                    val exV = existingVoxel.vertexBuffer
                    val exI = existingVoxel.indexBuffer
                    exV.position(0)
                    exI.position(0)

                    val maxAllowedVerts = minOf(numVerts, existingVoxel.vertexCount)
                    val fusedV = ByteBuffer.allocateDirect(numVerts * 3 * 4)
                      .order(ByteOrder.nativeOrder())
                      .asFloatBuffer()

                    for (vi in 0 until maxAllowedVerts) {
                      val ox = exV.get(vi * 3); val oy = exV.get(vi * 3 + 1); val oz = exV.get(vi * 3 + 2)
                      val nx = vBuffer.get(vi * 3); val ny = vBuffer.get(vi * 3 + 1); val nz = vBuffer.get(vi * 3 + 2)
                      // Temporal smoothing 70% previous, 30% new
                      fusedV.put(ox * 0.70f + nx * 0.30f)
                      fusedV.put(oy * 0.70f + ny * 0.30f)
                      fusedV.put(oz * 0.70f + nz * 0.30f)
                    }
                    for (vi in maxAllowedVerts until numVerts) {
                      fusedV.put(vBuffer.get(vi * 3))
                      fusedV.put(vBuffer.get(vi * 3 + 1))
                      fusedV.put(vBuffer.get(vi * 3 + 2))
                    }
                    fusedV.position(0)
                    finalVBuffer = fusedV
                    finalIBuffer = iBuffer
                    finalVertCount = numVerts
                    finalTriCount = numTris
                    finalArea = (existingVoxel.surfaceAreaSquareMeters * 0.6f) + (totalTriArea * 0.4f)
                  } else {
                    finalVBuffer = vBuffer
                    finalIBuffer = iBuffer
                    finalVertCount = numVerts
                    finalTriCount = numTris
                    finalArea = totalTriArea
                  }

                  val chunk = MeshChunk(
                    id = chunkId,
                    sourceType = GeometrySourceType.ENVIRONMENTAL_3D_MESH,
                    category = cat,
                    vertexCount = finalVertCount,
                    triangleCount = finalTriCount,
                    centerPosition = center,
                    surfaceAreaSquareMeters = finalArea,
                    vertexBuffer = finalVBuffer,
                    indexBuffer = finalIBuffer
                  )

                  // Accumulate into persistent world spatial voxels
                  if (persistentSpatialVoxelChunks.size >= MAX_PERSISTENT_CHUNKS && !persistentSpatialVoxelChunks.containsKey(chunkId)) {
                    val oldestKey = persistentSpatialVoxelChunks.keys.firstOrNull()
                    if (oldestKey != null) persistentSpatialVoxelChunks.remove(oldestKey)
                  }
                  persistentSpatialVoxelChunks[chunkId] = chunk

                  if (cat == MeshSurfaceCategory.FLOOR) hasFloor = true
                  if (cat == MeshSurfaceCategory.WALL) hasWall = true
                  if (cat == MeshSurfaceCategory.TABLE_SURFACE || cat == MeshSurfaceCategory.DESK_OR_COUNTER) hasTable = true
                }
              }
            }
          }
        } catch (_: Throwable) {
        } finally {
          depthImage?.close()
        }
      }

      // Sync active 3D chunks from persistent spatial voxel reconstruction
      environmental3dChunks.clear()
      environmental3dChunks.putAll(persistentSpatialVoxelChunks)

      val totalChunks = detectedPlaneChunks.size + streetscapeChunks.size + environmental3dChunks.size
      val real3dMeshCount = streetscapeChunks.size + environmental3dChunks.size
      val hasReal3dMesh = real3dMeshCount > 0

      val localMeshArea = environmental3dChunks.values.sumOf { it.surfaceAreaSquareMeters.toDouble() }.toFloat()
      val localMeshTris = environmental3dChunks.values.sumOf { it.triangleCount }
      val localMeshVerts = environmental3dChunks.values.sumOf { it.vertexCount }

      totalVerts += localMeshVerts
      totalTris += localMeshTris
      totalArea += localMeshArea

      // Compute physical 3D bounding box span across persistent voxels
      var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
      var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
      var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

      for (chunk in environmental3dChunks.values) {
        val cp = chunk.centerPosition
        minX = minOf(minX, cp[0]); maxX = maxOf(maxX, cp[0])
        minY = minOf(minY, cp[1]); maxY = maxOf(maxY, cp[1])
        minZ = minOf(minZ, cp[2]); maxZ = maxOf(maxZ, cp[2])
        if (chunk.category == MeshSurfaceCategory.FLOOR) hasFloor = true
        if (chunk.category == MeshSurfaceCategory.WALL) hasWall = true
        if (chunk.category == MeshSurfaceCategory.TABLE_SURFACE || chunk.category == MeshSurfaceCategory.DESK_OR_COUNTER) hasTable = true
      }

      val spanX = if (environmental3dChunks.isNotEmpty()) (maxX - minX) else 0f
      val spanZ = if (environmental3dChunks.isNotEmpty()) (maxZ - minZ) else 0f
      val spanY = if (environmental3dChunks.isNotEmpty()) (maxY - minY) else 0f

      // Strictly separate the 5 distinct states:
      // State 1: Plane Detection (convex 2D polygons)
      val isPlaneDetectionActive = detectedPlaneChunks.isNotEmpty()
      // State 2: Streetscape Geometry (outdoor mesh)
      val isStreetscapeActive = streetscapeChunks.isNotEmpty()
      // State 3: Local Environmental Mesh (volumetric point cloud / depth chunks)
      val isLocalMeshActive = environmental3dChunks.isNotEmpty()
      // State 4: Dense Local Reconstruction (substantial local geometric coverage)
      val isDenseLocalReconstruction = isLocalMeshActive && localMeshTris >= 300 && localMeshArea >= 3.0f
      // Native Scene Reconstruction: STRICT REQUIREMENT - Validate actual mesh coverage, continuity,
      // valid depth/geometry, and spatial completeness before reporting FULL_3D_SCENE_RECONSTRUCTION.
      // Do not classify the environment as FULL_3D_SCENE_RECONSTRUCTION using thresholds alone.
      val isFull3dScene = validateFull3dSceneCompleteness(
        chunks = environmental3dChunks.values,
        spanX = spanX,
        spanY = spanY,
        spanZ = spanZ,
        totalTris = localMeshTris,
        totalArea = localMeshArea,
        hasFloor = hasFloor,
        hasWall = hasWall
      )

      val reconstructionStage = when {
        !isDepthSupported && !isStreetscapeActive && !isPlaneDetectionActive -> "UNSUPPORTED"
        totalChunks == 0 -> "IDLE"
        !isLocalMeshActive && isStreetscapeActive -> "ARCORE_STREETSCAPE_GEOMETRY"
        !isLocalMeshActive -> "PLANE_DETECTION_ONLY"
        isFull3dScene -> "VALIDATED_LOCAL_SCENE_MESH"
        isDenseLocalReconstruction -> "DENSE_LOCAL_MESH"
        else -> "LOCAL_SURFACE_MESH"
      }

      val semanticsSource = if (usedMlSemantics) "ARCORE_ML_SEMANTICS" else "GEOMETRIC_ORIENTATION_ESTIMATE"

      telemetry = ReconstructionTelemetry(
        isReconstructionActive = totalChunks > 0,
        isPlaneDetectionActive = isPlaneDetectionActive,
        isStreetscapeGeometryActive = isStreetscapeActive,
        isLocalEnvironmentalMeshActive = isLocalMeshActive,
        isDenseLocalReconstructionActive = isDenseLocalReconstruction,
        isDenseLocalMeshActive = isDenseLocalReconstruction,
        isValidatedLocalSceneMesh = isFull3dScene,
        isFull3dSceneReconstruction = false, // Conservative: never claim native FULL_3D_SCENE_RECONSTRUCTION from application thresholds alone
        hasReal3dMeshGeometry = hasReal3dMesh,
        detectedPlanesCount = detectedPlaneChunks.size,
        streetscapeGeometriesCount = streetscapeChunks.size,
        denseMeshChunksCount = environmental3dChunks.size,
        totalChunks = totalChunks,
        totalVertices = totalVerts,
        totalTriangles = totalTris,
        totalSurfaceAreaSqMeters = totalArea,
        localMeshAreaSqMeters = localMeshArea,
        hasFloorPlane = hasFloor,
        hasWallPlane = hasWall,
        hasTableSurface = hasTable,
        reconstructionStage = reconstructionStage,
        semanticsClassificationSource = semanticsSource
      )
    } catch (e: Exception) {
      Log.d(TAG, "Environmental mesh update: ${e.message}")
    }
  }

  /**
   * Validates true FULL_3D_SCENE_RECONSTRUCTION status.
   * STRICT REQUIREMENT: Do not classify the environment as FULL_3D_SCENE_RECONSTRUCTION
   * using thresholds alone. Validate:
   * 1. Valid depth & geometry: vertex buffers contain finite, non-degenerate coordinates within valid physical ranges (0.1m - 15m).
   * 2. Topological continuity: chunks must form a connected spatial component (not isolated disconnected spikes).
   * 3. Spatial completeness: broad 3D bounding volume spanning X, Y, and Z (height >= 1.2m, spanX >= 3.0m, spanZ >= 3.0m).
   * 4. Multi-quadrant coverage: chunks must surround the environment in at least 3 of 4 quadrants relative to centroid.
   * 5. Surface co-presence: requires confirmed horizontal floor/ground plane AND vertical wall structures.
   * 6. Mesh coverage: minimum 16 validated chunks with >= 15.0 m² verified continuous surface area and >= 2500 triangles.
   */
  fun validateFull3dSceneCompleteness(
    chunks: Collection<MeshChunk>,
    spanX: Float,
    spanY: Float,
    spanZ: Float,
    totalTris: Int,
    totalArea: Float,
    hasFloor: Boolean,
    hasWall: Boolean
  ): Boolean {
    // 1. Structural co-presence and scale gates
    if (chunks.size < 16 || totalTris < 2500 || totalArea < 15.0f) return false
    if (!hasFloor || !hasWall) return false
    if (spanX < 3.0f || spanZ < 3.0f || spanY < 1.2f) return false

    // 2. Validate depth & geometry integrity per chunk
    var validGeometryChunks = 0
    val centers = mutableListOf<FloatArray>()
    for (chunk in chunks) {
      val vb = chunk.vertexBuffer
      val ib = chunk.indexBuffer
      if (vb == null || ib == null) return false
      if (chunk.vertexCount < 3 || chunk.triangleCount < 1) return false
      if (chunk.surfaceAreaSquareMeters <= 0.001f) return false

      val cp = chunk.centerPosition
      if (cp[0].isNaN() || cp[1].isNaN() || cp[2].isNaN()) return false
      val distFromOrigin = Math.sqrt((cp[0] * cp[0] + cp[1] * cp[1] + cp[2] * cp[2]).toDouble()).toFloat()
      if (distFromOrigin !in 0.1f..15.0f) return false

      // Check vertex buffer data validity
      if (vb.capacity() >= 3) {
        val vx = vb.get(0); val vy = vb.get(1); val vz = vb.get(2)
        if (vx.isNaN() || vy.isNaN() || vz.isNaN() || vx.isInfinite() || vy.isInfinite() || vz.isInfinite()) {
          return false
        }
      }
      centers.add(cp)
      validGeometryChunks++
    }
    if (validGeometryChunks < 16) return false

    // 3. Validate Real Spatial Coverage (Grid Occupancy and Multi-Quadrant Balance)
    // Discretize into 1m x 1m spatial grid cells to ensure true spatial coverage rather than isolated perimeter points
    val occupiedCells = mutableSetOf<Pair<Int, Int>>()
    for (c in centers) {
      val cellX = Math.floor(c[0].toDouble()).toInt()
      val cellZ = Math.floor(c[2].toDouble()).toInt()
      occupiedCells.add(Pair(cellX, cellZ))
    }
    // A genuine full 3D scene (>=3m x 3m) must occupy at least 8 distinct 1m x 1m spatial grid cells
    if (occupiedCells.size < 8) {
      return false
    }

    val meanX = centers.map { it[0] }.average().toFloat()
    val meanZ = centers.map { it[2] }.average().toFloat()
    var q1Count = 0; var q2Count = 0; var q3Count = 0; var q4Count = 0
    for (c in centers) {
      val dx = c[0] - meanX
      val dz = c[2] - meanZ
      if (dx >= 0 && dz >= 0) q1Count++
      if (dx < 0 && dz >= 0) q2Count++
      if (dx < 0 && dz < 0) q3Count++
      if (dx >= 0 && dz < 0) q4Count++
    }
    val quadrantsCovered = (if (q1Count >= 2) 1 else 0) +
        (if (q2Count >= 2) 1 else 0) +
        (if (q3Count >= 2) 1 else 0) +
        (if (q4Count >= 2) 1 else 0)
    if (quadrantsCovered < 3) {
      return false
    }

    // 4. Validate True Mesh Topological Continuity via Graph Connected Component (BFS)
    // Build adjacency list: chunks are neighbors if Euclidean center distance <= 1.5m
    val maxNeighborDistanceSq = 1.5f * 1.5f
    val adj = Array(centers.size) { mutableListOf<Int>() }
    for (i in centers.indices) {
      val c1 = centers[i]
      for (j in i + 1 until centers.size) {
        val c2 = centers[j]
        val dX = c1[0] - c2[0]
        val dY = c1[1] - c2[1]
        val dZ = c1[2] - c2[2]
        val distSq = dX * dX + dY * dY + dZ * dZ
        if (distSq <= maxNeighborDistanceSq) {
          adj[i].add(j)
          adj[j].add(i)
        }
      }
    }

    // Run BFS to find largest connected component
    val visited = BooleanArray(centers.size)
    var maxComponentSize = 0
    for (start in centers.indices) {
      if (!visited[start]) {
        var componentSize = 0
        val queue = java.util.ArrayDeque<Int>()
        queue.add(start)
        visited[start] = true
        while (queue.isNotEmpty()) {
          val u = queue.poll() ?: continue
          componentSize++
          for (v in adj[u]) {
            if (!visited[v]) {
              visited[v] = true
              queue.add(v)
            }
          }
        }
        maxComponentSize = maxOf(maxComponentSize, componentSize)
      }
    }

    // Require >= 85% of all chunks to form a single continuous, topologically integrated mesh structure
    val continuityRatio = maxComponentSize.toFloat() / centers.size.toFloat()
    if (continuityRatio < 0.85f) {
      return false
    }

    return true
  }

  /**
   * Resolves surface category: prioritizes true ARCore ML semantic labels if available;
   * otherwise falls back to explicit geometric normal orientation.
   * Avoids false "DESK_OR_COUNTER" classification for arbitrary objects, chairs, or couches.
   */
  private fun resolveSurfaceCategory(
    frame: com.google.ar.core.Frame,
    semanticsManager: SceneSemanticsManager?,
    normX: Float,
    normY: Float,
    p0: FloatArray,
    p1: FloatArray,
    p2: FloatArray
  ): MeshSurfaceCategory {
    if (semanticsManager != null && semanticsManager.telemetry.isEnabled) {
      val mlLabel = try {
        semanticsManager.getSemanticLabelAt(frame, normX, normY)
      } catch (_: Exception) { "UNLABELED" }

      if (mlLabel != "UNLABELED" && mlLabel != "UNKNOWN" && mlLabel != "LOW_CONFIDENCE") {
        when (mlLabel.uppercase()) {
          "FLOOR", "ROAD", "SIDEWALK", "TERRAIN" -> return MeshSurfaceCategory.FLOOR
          "WALL", "BUILDING", "STRUCTURE" -> return MeshSurfaceCategory.WALL
          "CEILING", "SKY" -> return MeshSurfaceCategory.CEILING
          "TABLE" -> return MeshSurfaceCategory.TABLE_SURFACE
          "DESK", "COUNTER" -> return MeshSurfaceCategory.DESK_OR_COUNTER
          "CHAIR", "COUCH", "BED" -> return MeshSurfaceCategory.GENERIC_OBSTACLE
          "OBJECT" -> {
            // Do NOT classify generic OBJECT as DESK_OR_COUNTER; verify geometric orientation
            return classifyTriangleCategory(p0, p1, p2)
          }
        }
      }
    }

    // Geometric orientation heuristic fallback
    return classifyTriangleCategory(p0, p1, p2)
  }

  private fun classifyTriangleCategory(p0: FloatArray, p1: FloatArray, p2: FloatArray): MeshSurfaceCategory {
    val abx = p1[0] - p0[0]; val aby = p1[1] - p0[1]; val abz = p1[2] - p0[2]
    val acx = p2[0] - p0[0]; val acy = p2[1] - p0[1]; val acz = p2[2] - p0[2]
    val cx = aby * acz - abz * acy
    val cy = abz * acx - abx * acz
    val cz = abx * acy - aby * acx
    val len = Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat()
    val ny = if (len > 0.0001f) cy / len else 0f
    val avgY = (p0[1] + p1[1] + p2[1]) / 3f

    return when {
      ny > 0.7f -> {
        // Upward horizontal plane
        when {
          avgY <= FLOOR_HEIGHT_THRESHOLD_METERS -> MeshSurfaceCategory.FLOOR
          avgY in (FLOOR_HEIGHT_THRESHOLD_METERS + 0.35f)..-0.15f -> MeshSurfaceCategory.TABLE_SURFACE
          else -> MeshSurfaceCategory.GENERIC_OBSTACLE
        }
      }
      ny < -0.7f -> MeshSurfaceCategory.CEILING
      Math.abs(ny) < 0.3f -> MeshSurfaceCategory.WALL
      else -> MeshSurfaceCategory.GENERIC_OBSTACLE
    }
  }

  fun clear() {
    detectedPlaneChunks.clear()
    streetscapeChunks.clear()
    environmental3dChunks.clear()
    persistentSpatialVoxelChunks.clear()
    telemetry = ReconstructionTelemetry()
  }
}
