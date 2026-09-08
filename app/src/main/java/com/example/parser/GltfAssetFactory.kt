package com.example.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.SpatialModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.sin

/**
 * Factory and loader for binary GLB and glTF 2.0 models for Filament gltfio engine.
 * Generates valid GLB containers (Header + JSON Chunk + BIN Chunk) with PBR materials,
 * vertex buffers, normal buffers, and index buffers.
 * Supports streaming direct memory loading for large 3D models up to and beyond 250 MB.
 */
object GltfAssetFactory {

  private const val TAG = "GltfAssetFactory"
  const val GLB_MAGIC = 0x46546C67 // "glTF" in ASCII little-endian
  const val MAX_SUPPORTED_FILE_SIZE_BYTES = 500L * 1024L * 1024L // 500 MB capacity ceiling

  /**
   * Validates whether a direct ByteBuffer begins with the standard 12-byte binary glTF (GLB) header.
   */
  fun validateGlbHeader(buffer: ByteBuffer): Boolean {
    if (buffer.capacity() < 12) return false
    val pos = buffer.position()
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val magic = buffer.getInt(0)
    val version = buffer.getInt(4)
    val length = buffer.getInt(8)
    buffer.position(pos)
    val isValid = magic == GLB_MAGIC && version in 1..2 && length <= buffer.capacity() && length > 12
    return isValid
  }

  /**
   * Checks whether a buffer is formatted as a JSON glTF container.
   */
  fun isJsonGltf(buffer: ByteBuffer): Boolean {
    if (buffer.capacity() < 10) return false
    val limit = minOf(buffer.capacity(), 256)
    for (i in 0 until limit) {
      val b = buffer.get(i).toInt().toChar()
      if (!b.isWhitespace()) {
        return b == '{'
      }
    }
    return false
  }

  /**
   * Validates whether a direct ByteBuffer is either a valid binary GLB container
   * or a valid glTF 2.0 JSON container.
   */
  fun validateGlbOrGltf(buffer: ByteBuffer): Boolean {
    val isGlb = validateGlbHeader(buffer)
    if (isGlb) return true
    val isGltf = isJsonGltf(buffer)
    if (isGltf) {
      Log.i(TAG, "Detected valid standalone glTF 2.0 JSON asset.")
      return true
    }
    Log.w(TAG, "Asset buffer is neither a valid GLB container nor a glTF JSON container.")
    return false
  }

  /**
   * Reads raw bytes from an input stream into a direct ByteBuffer for Filament.
   * Employs chunked buffered streaming (256 KB) without calling readBytes(),
   * preventing duplicate full-file memory allocations.
   */
  fun readToDirectByteBuffer(
    inputStream: InputStream,
    expectedTotalBytes: Long = -1L,
    onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
  ): ByteBuffer {
    val tempFile = File.createTempFile("stream_glb_", ".bin")
    try {
      FileOutputStream(tempFile).use { out ->
        val buffer = ByteArray(256 * 1024)
        var read: Int
        var total = 0L
        while (inputStream.read(buffer).also { read = it } != -1) {
          out.write(buffer, 0, read)
          total += read
          onProgress?.invoke(total, if (expectedTotalBytes > 0) expectedTotalBytes else -1L)
        }
        out.flush()
      }

      val fileSize = tempFile.length()
      if (fileSize > MAX_SUPPORTED_FILE_SIZE_BYTES) {
        throw IllegalArgumentException("Asset size ($fileSize bytes) exceeds maximum supported 500 MB.")
      }

      val directBuffer = ByteBuffer.allocateDirect(fileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
      FileInputStream(tempFile).use { inStream ->
        val channel = inStream.channel
        channel.read(directBuffer)
      }
      directBuffer.rewind()
      return directBuffer
    } finally {
      tempFile.delete()
    }
  }

  private fun queryFileSize(context: Context, uri: Uri): Long {
    var size = -1L
    try {
      context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) {
          size = cursor.getLong(idx)
        }
      }
    } catch (_: Throwable) {}
    return size
  }

  /**
   * Reads a model from a content Uri directly into an off-heap direct ByteBuffer.
   * Handles files up to at least 250 MB without OutOfMemoryError by querying file size directly
   * from the ParcelFileDescriptor and streaming in 256 KB native chunks with zero heap duplication.
   */
  fun readUriToDirectByteBuffer(
    context: Context,
    uri: Uri,
    onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
  ): ByteBuffer? {
    return try {
      val pfd = context.contentResolver.openFileDescriptor(uri, "r")
      if (pfd != null) {
        pfd.use { descriptor ->
          val statSize = descriptor.statSize
          if (statSize > 0) {
            if (statSize > MAX_SUPPORTED_FILE_SIZE_BYTES) {
              Log.e(TAG, "File size ($statSize bytes) exceeds maximum supported 500 MB.")
              return null
            }
            // Allocate directly in off-heap native memory for Filament gltfio
            val directBuffer = ByteBuffer.allocateDirect(statSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            FileInputStream(descriptor.fileDescriptor).use { inStream ->
              val channel = inStream.channel
              var totalRead = 0L
              val chunkSize = 256 * 1024
              val slice = directBuffer.duplicate()
              while (totalRead < statSize) {
                val nextChunk = minOf(chunkSize.toLong(), statSize - totalRead).toInt()
                slice.position(totalRead.toInt())
                slice.limit(totalRead.toInt() + nextChunk)
                val bytesRead = channel.read(slice)
                if (bytesRead <= 0) break
                totalRead += bytesRead
                onProgress?.invoke(totalRead, statSize)
              }
            }
            directBuffer.rewind()
            if (validateGlbOrGltf(directBuffer)) {
              Log.i(TAG, "Successfully streamed ${statSize / (1024 * 1024)} MB GLB/glTF asset into direct ByteBuffer.")
              directBuffer
            } else {
              Log.w(TAG, "Validation rejected corrupt/invalid GLB/glTF container.")
              null
            }
          } else {
            // Fallback for providers that do not report statSize
            val reportedSize = queryFileSize(context, uri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
              val buf = readToDirectByteBuffer(stream, reportedSize, onProgress)
              if (validateGlbOrGltf(buf)) buf else null
            }
          }
        }
      } else {
        val reportedSize = queryFileSize(context, uri)
        context.contentResolver.openInputStream(uri)?.use { stream ->
          val buf = readToDirectByteBuffer(stream, reportedSize, onProgress)
          if (validateGlbOrGltf(buf)) buf else null
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error loading large GLB asset: ${e.message}", e)
      null
    }
  }

  /**
   * Creates a bundled valid GLB container for 3D presets.
   */
  fun createDroneGlb(): ByteBuffer {
    return buildProceduralGlb(
      name = "Autonomous Spatial Drone",
      primaryColor = floatArrayOf(0.15f, 0.75f, 1.0f, 1.0f),
      secondaryColor = floatArrayOf(0.1f, 0.12f, 0.18f, 1.0f),
      metallic = 0.85f,
      roughness = 0.25f,
      shapeType = 1
    )
  }

  fun createSciFiCoreGlb(): ByteBuffer {
    return buildProceduralGlb(
      name = "Quantum Fusion Core",
      primaryColor = floatArrayOf(0.95f, 0.35f, 0.1f, 1.0f),
      secondaryColor = floatArrayOf(0.15f, 0.15f, 0.2f, 1.0f),
      metallic = 0.9f,
      roughness = 0.15f,
      shapeType = 2
    )
  }

  fun createMechGlb(): ByteBuffer {
    return buildProceduralGlb(
      name = "Cybernetic Mech Rig",
      primaryColor = floatArrayOf(0.3f, 0.85f, 0.4f, 1.0f),
      secondaryColor = floatArrayOf(0.12f, 0.14f, 0.16f, 1.0f),
      metallic = 0.7f,
      roughness = 0.35f,
      shapeType = 3
    )
  }

  fun createAstronautGlb(): ByteBuffer {
    return buildProceduralGlb(
      name = "Astronaut Helmet",
      primaryColor = floatArrayOf(0.92f, 0.92f, 0.95f, 1.0f),
      secondaryColor = floatArrayOf(0.85f, 0.65f, 0.15f, 1.0f),
      metallic = 0.4f,
      roughness = 0.2f,
      shapeType = 4
    )
  }

  fun getPresetModels(): List<SpatialModel> {
    return listOf(
      SpatialModel(
        id = "drone_v1",
        title = "Spatial Drone X-1",
        description = "Quad-rotor drone with lidar sensor pod and carbon-composite chassis.",
        category = "Robotics & Aerial",
        vertexCount = 160,
        triangleCount = 140,
        isCustomLoaded = false,
        hasAnimations = false,
        animationDurationSec = 0.0f
      ),
      SpatialModel(
        id = "scifi_core_v1",
        title = "Quantum Fusion Core",
        description = "Magnetic confinement reactor with glowing plasma containment field.",
        category = "Energy & Sci-Fi",
        vertexCount = 703,
        triangleCount = 1216,
        isCustomLoaded = false,
        hasAnimations = false,
        animationDurationSec = 0.0f
      ),
      SpatialModel(
        id = "mech_rig_v1",
        title = "Cybernetic Mech",
        description = "Heavy bipedal frame with hydraulic actuators and reinforced armor plating.",
        category = "Cybernetics",
        vertexCount = 120,
        triangleCount = 60,
        isCustomLoaded = false,
        hasAnimations = false,
        animationDurationSec = 0.0f
      ),
      SpatialModel(
        id = "astronaut_v1",
        title = "EVA Astronaut Helmet",
        description = "Pressurized extravehicular helmet with gold-coated solar radiation visor.",
        category = "Aerospace & Sci-Fi",
        vertexCount = 385,
        triangleCount = 660,
        isCustomLoaded = false,
        hasAnimations = false,
        animationDurationSec = 0.0f
      )
    )
  }

  fun getPresetGlbBuffer(modelId: String): ByteBuffer {
    return when (modelId) {
      "drone_v1" -> createDroneGlb()
      "scifi_core_v1" -> createSciFiCoreGlb()
      "mech_rig_v1" -> createMechGlb()
      "astronaut_v1" -> createAstronautGlb()
      else -> createDroneGlb()
    }
  }

  fun createAnimatedDroneGlb(): ByteBuffer {
    return buildProceduralGlb(
      name = "Quantum_Drone_Animated",
      primaryColor = floatArrayOf(0.15f, 0.45f, 0.95f, 1.0f),
      secondaryColor = floatArrayOf(0.05f, 0.15f, 0.35f, 1.0f),
      metallic = 0.85f,
      roughness = 0.25f,
      shapeType = 0,
      includeAnimations = true
    )
  }

  /**
   * Generates a fully conformant binary glTF 2.0 (.glb) container with valid
   * Magic (0x46546C67), version 2, JSON header chunk, binary geometry chunk,
   * PBR materials, and optional multi-track skeletal/transform animations.
   */
  fun buildProceduralGlb(
    name: String,
    primaryColor: FloatArray,
    secondaryColor: FloatArray,
    metallic: Float,
    roughness: Float,
    shapeType: Int,
    includeAnimations: Boolean = false
  ): ByteBuffer {
    // Generate vertices, normals, UVs and indices
    val (vertices, normals, indices) = generateGeometry(shapeType)

    val vertexBytes = vertices.size * 4
    val normalBytes = normals.size * 4
    val indexBytes = indices.size * 2 // 16-bit indices

    // Procedural Animations (when requested):
    // Track 0: Hover & Pulse (translation Y bobbing between -0.08m and +0.08m)
    // Track 1: 360 Turntable Orbit (continuous full 360 rotation around Y axis)
    val animTimes = if (includeAnimations) floatArrayOf(0.0f, 1.0f, 2.0f, 3.0f, 4.0f) else floatArrayOf()
    val animTranslations = if (includeAnimations) floatArrayOf(
      0.0f, 0.0f, 0.0f,
      0.0f, 0.08f, 0.0f,
      0.0f, 0.0f, 0.0f,
      0.0f, -0.08f, 0.0f,
      0.0f, 0.0f, 0.0f
    ) else floatArrayOf()
    val animRotations = if (includeAnimations) floatArrayOf(
      0.0f, 0.0f, 0.0f, 1.0f,
      0.0f, 0.7071068f, 0.0f, 0.7071068f,
      0.0f, 1.0f, 0.0f, 0.0f,
      0.0f, 0.7071068f, 0.0f, -0.7071068f,
      0.0f, 0.0f, 0.0f, 1.0f
    ) else floatArrayOf()

    val timeBytes = animTimes.size * 4
    val transBytes = animTranslations.size * 4
    val rotBytes = animRotations.size * 4
    val animTotalBytes = timeBytes + transBytes + rotBytes

    // Pad indices so animation data starts on a 4-byte boundary
    val rawGeomBytes = vertexBytes + normalBytes + indexBytes
    val indexPad = (4 - (rawGeomBytes % 4)) % 4

    val binBuffer = ByteBuffer.allocate(rawGeomBytes + indexPad + animTotalBytes).order(ByteOrder.LITTLE_ENDIAN)

    // Append vertices
    val posByteOffset = 0
    for (v in vertices) binBuffer.putFloat(v)

    // Append normals
    val normalByteOffset = posByteOffset + vertexBytes
    for (n in normals) binBuffer.putFloat(n)

    // Append indices
    val indexByteOffset = normalByteOffset + normalBytes
    for (idx in indices) binBuffer.putShort(idx)

    // Index padding bytes to align to 4-byte boundary
    for (p in 0 until indexPad) binBuffer.put(0.toByte())

    // Append animation data (if present)
    val animByteOffset = indexByteOffset + indexBytes + indexPad
    val timeByteOffsetInAnim = 0
    if (includeAnimations) {
      for (t in animTimes) binBuffer.putFloat(t)
      for (tr in animTranslations) binBuffer.putFloat(tr)
      for (r in animRotations) binBuffer.putFloat(r)
    }

    val binBytes = binBuffer.array()
    val binPadding = (4 - (binBytes.size % 4)) % 4
    val totalBinLength = binBytes.size + binPadding

    // Min & Max bounds for accessor 0 (POSITION)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in vertices.indices step 3) {
      minX = minOf(minX, vertices[i]); maxX = maxOf(maxX, vertices[i])
      minY = minOf(minY, vertices[i + 1]); maxY = maxOf(maxY, vertices[i + 1])
      minZ = minOf(minZ, vertices[i + 2]); maxZ = maxOf(maxZ, vertices[i + 2])
    }

    val vertexCount = vertices.size / 3
    val indexCount = indices.size

    val animAccessorsJson = if (includeAnimations) {
      """,
        {
          "bufferView": 2,
          "byteOffset": $timeByteOffsetInAnim,
          "componentType": 5126,
          "count": ${animTimes.size},
          "type": "SCALAR",
          "max": [4.0],
          "min": [0.0]
        },
        {
          "bufferView": 2,
          "byteOffset": $timeBytes,
          "componentType": 5126,
          "count": ${animTimes.size},
          "type": "VEC3"
        },
        {
          "bufferView": 2,
          "byteOffset": ${timeBytes + transBytes},
          "componentType": 5126,
          "count": ${animTimes.size},
          "type": "VEC4"
        }"""
    } else ""

    val animBufferViewJson = if (includeAnimations) {
      """,
        {
          "buffer": 0,
          "byteOffset": $animByteOffset,
          "byteLength": $animTotalBytes
        }"""
    } else ""

    val animationsBlockJson = if (includeAnimations) {
      """,
      "animations": [
        {
          "name": "Hover & Pulse",
          "channels": [
            {
              "sampler": 0,
              "target": { "node": 0, "path": "translation" }
            }
          ],
          "samplers": [
            {
              "input": 3,
              "interpolation": "LINEAR",
              "output": 4
            }
          ]
        },
        {
          "name": "360 Turntable Orbit",
          "channels": [
            {
              "sampler": 0,
              "target": { "node": 0, "path": "rotation" }
            }
          ],
          "samplers": [
            {
              "input": 3,
              "interpolation": "LINEAR",
              "output": 5
            }
          ]
        }
      ]"""
    } else ""

    val jsonString = """
    {
      "asset": { "version": "2.0", "generator": "AI Studio Filament Spatial Engine" },
      "scenes": [{ "nodes": [0] }],
      "nodes": [{ "name": "$name", "mesh": 0 }],
      "meshes": [{
        "name": "$name",
        "primitives": [{
          "attributes": {
            "POSITION": 0,
            "NORMAL": 1
          },
          "indices": 2,
          "material": 0,
          "mode": 4
        }]
      }],
      "materials": [{
        "name": "Pbr_${name}",
        "pbrMetallicRoughness": {
          "baseColorFactor": [${primaryColor[0]}, ${primaryColor[1]}, ${primaryColor[2]}, ${primaryColor[3]}],
          "metallicFactor": $metallic,
          "roughnessFactor": $roughness
        },
        "doubleSided": true
      }],
      "accessors": [
        {
          "bufferView": 0,
          "byteOffset": $posByteOffset,
          "componentType": 5126,
          "count": $vertexCount,
          "type": "VEC3",
          "max": [$maxX, $maxY, $maxZ],
          "min": [$minX, $minY, $minZ]
        },
        {
          "bufferView": 0,
          "byteOffset": $normalByteOffset,
          "componentType": 5126,
          "count": $vertexCount,
          "type": "VEC3"
        },
        {
          "bufferView": 1,
          "byteOffset": 0,
          "componentType": 5123,
          "count": $indexCount,
          "type": "SCALAR"
        }$animAccessorsJson
      ],
      "bufferViews": [
        {
          "buffer": 0,
          "byteOffset": 0,
          "byteLength": ${vertexBytes + normalBytes},
          "target": 34962
        },
        {
          "buffer": 0,
          "byteOffset": $indexByteOffset,
          "byteLength": $indexBytes,
          "target": 34963
        }$animBufferViewJson
      ]$animationsBlockJson,
      "buffers": [{
        "byteLength": ${binBytes.size}
      }]
    }
    """.trimIndent()

    val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
    val jsonPadding = (4 - (jsonBytes.size % 4)) % 4
    val totalJsonLength = jsonBytes.size + jsonPadding

    val totalGlbLength = 12 + (8 + totalJsonLength) + (8 + totalBinLength)
    val glbBuffer = ByteBuffer.allocateDirect(totalGlbLength).order(ByteOrder.LITTLE_ENDIAN)

    // GLB Header: magic (0x46546C67), version (2), length
    glbBuffer.putInt(0x46546C67)
    glbBuffer.putInt(2)
    glbBuffer.putInt(totalGlbLength)

    // JSON Chunk: length, type (0x4E4F534A "JSON"), data + padding spaces (0x20)
    glbBuffer.putInt(totalJsonLength)
    glbBuffer.putInt(0x4E4F534A)
    glbBuffer.put(jsonBytes)
    for (i in 0 until jsonPadding) glbBuffer.put(0x20.toByte())

    // BIN Chunk: length, type (0x004E4942 "BIN\0"), data + padding zeros (0x00)
    glbBuffer.putInt(totalBinLength)
    glbBuffer.putInt(0x004E4942)
    glbBuffer.put(binBytes)
    for (i in 0 until binPadding) glbBuffer.put(0x00.toByte())

    glbBuffer.rewind()
    return glbBuffer
  }

  private fun generateGeometry(shapeType: Int): Triple<FloatArray, FloatArray, ShortArray> {
    val vertices = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val indices = mutableListOf<Short>()

    when (shapeType) {
      1 -> { // Drone: central body + 4 rotor arms + 4 pods
        addCube(vertices, normals, indices, 0f, 0f, 0f, 0.4f, 0.12f, 0.4f)
        addCylinder(vertices, normals, indices, 0.45f, 0.05f, 0.45f, 0.22f, 0.03f, 16)
        addCylinder(vertices, normals, indices, -0.45f, 0.05f, 0.45f, 0.22f, 0.03f, 16)
        addCylinder(vertices, normals, indices, 0.45f, 0.05f, -0.45f, 0.22f, 0.03f, 16)
        addCylinder(vertices, normals, indices, -0.45f, 0.05f, -0.45f, 0.22f, 0.03f, 16)
      }
      2 -> { // Reactor Core: spherical shell + containment rings
        addSphere(vertices, normals, indices, 0f, 0f, 0f, 0.35f, 16, 16)
        addTorus(vertices, normals, indices, 0f, 0f, 0f, 0.55f, 0.06f, 20, 8)
        addTorus(vertices, normals, indices, 0f, 0f, 0f, 0.72f, 0.04f, 24, 8)
      }
      3 -> { // Mech: torso + legs + shoulder armor
        addCube(vertices, normals, indices, 0f, 0.2f, 0f, 0.35f, 0.4f, 0.25f)
        addCube(vertices, normals, indices, -0.2f, -0.3f, 0f, 0.12f, 0.45f, 0.12f)
        addCube(vertices, normals, indices, 0.2f, -0.3f, 0f, 0.12f, 0.45f, 0.12f)
        addCube(vertices, normals, indices, -0.35f, 0.35f, 0f, 0.16f, 0.16f, 0.2f)
        addCube(vertices, normals, indices, 0.35f, 0.35f, 0f, 0.16f, 0.16f, 0.2f)
      }
      else -> { // Helmet: dome + visor
        addSphere(vertices, normals, indices, 0f, 0.05f, 0f, 0.45f, 18, 18)
        addCube(vertices, normals, indices, 0f, 0.05f, 0.25f, 0.38f, 0.24f, 0.2f)
      }
    }

    return Triple(vertices.toFloatArray(), normals.toFloatArray(), indices.toShortArray())
  }

  private fun addCube(
    verts: MutableList<Float>,
    norms: MutableList<Float>,
    inds: MutableList<Short>,
    cx: Float, cy: Float, cz: Float,
    sx: Float, sy: Float, sz: Float
  ) {
    val startIdx = (verts.size / 3).toShort()
    val hx = sx / 2f; val hy = sy / 2f; val hz = sz / 2f

    // 8 box corners
    val c = arrayOf(
      floatArrayOf(cx - hx, cy - hy, cz + hz), floatArrayOf(cx + hx, cy - hy, cz + hz),
      floatArrayOf(cx + hx, cy + hy, cz + hz), floatArrayOf(cx - hx, cy + hy, cz + hz),
      floatArrayOf(cx - hx, cy - hy, cz - hz), floatArrayOf(cx + hx, cy - hy, cz - hz),
      floatArrayOf(cx + hx, cy + hy, cz - hz), floatArrayOf(cx - hx, cy + hy, cz - hz)
    )

    fun addFace(p0: Int, p1: Int, p2: Int, p3: Int, nx: Float, ny: Float, nz: Float) {
      val base = (verts.size / 3).toShort()
      for (p in arrayOf(p0, p1, p2, p3)) {
        verts.addAll(listOf(c[p][0], c[p][1], c[p][2]))
        norms.addAll(listOf(nx, ny, nz))
      }
      inds.addAll(listOf(base, (base + 1).toShort(), (base + 2).toShort(), base, (base + 2).toShort(), (base + 3).toShort()))
    }

    addFace(0, 1, 2, 3, 0f, 0f, 1f)  // Front
    addFace(5, 4, 7, 6, 0f, 0f, -1f) // Back
    addFace(4, 0, 3, 7, -1f, 0f, 0f) // Left
    addFace(1, 5, 6, 2, 1f, 0f, 0f)  // Right
    addFace(3, 2, 6, 7, 0f, 1f, 0f)  // Top
    addFace(4, 5, 1, 0, 0f, -1f, 0f) // Bottom
  }

  private fun addSphere(
    verts: MutableList<Float>,
    norms: MutableList<Float>,
    inds: MutableList<Short>,
    cx: Float, cy: Float, cz: Float,
    radius: Float, rings: Int, sectors: Int
  ) {
    val startIdx = (verts.size / 3).toShort()
    for (r in 0..rings) {
      val lat = Math.PI * r.toDouble() / rings.toDouble() - Math.PI / 2.0
      val y = radius * sin(lat).toFloat()
      val cosLat = cos(lat).toFloat()

      for (s in 0..sectors) {
        val lon = 2.0 * Math.PI * s.toDouble() / sectors.toDouble()
        val x = radius * cosLat * cos(lon).toFloat()
        val z = radius * cosLat * sin(lon).toFloat()

        verts.addAll(listOf(cx + x, cy + y, cz + z))
        norms.addAll(listOf(x / radius, y / radius, z / radius))
      }
    }

    for (r in 0 until rings) {
      for (s in 0 until sectors) {
        val first = (startIdx + r * (sectors + 1) + s).toShort()
        val second = (first + sectors + 1).toShort()

        inds.addAll(listOf(first, second, (first + 1).toShort()))
        inds.addAll(listOf(second, (second + 1).toShort(), (first + 1).toShort()))
      }
    }
  }

  private fun addCylinder(
    verts: MutableList<Float>,
    norms: MutableList<Float>,
    inds: MutableList<Short>,
    cx: Float, cy: Float, cz: Float,
    radius: Float, height: Float, segments: Int
  ) {
    val startIdx = (verts.size / 3).toShort()
    val halfH = height / 2f

    for (i in 0..segments) {
      val angle = 2.0 * Math.PI * i.toDouble() / segments.toDouble()
      val x = radius * cos(angle).toFloat()
      val z = radius * sin(angle).toFloat()

      // Top vertex
      verts.addAll(listOf(cx + x, cy + halfH, cz + z))
      norms.addAll(listOf(x / radius, 0f, z / radius))

      // Bottom vertex
      verts.addAll(listOf(cx + x, cy - halfH, cz + z))
      norms.addAll(listOf(x / radius, 0f, z / radius))
    }

    for (i in 0 until segments) {
      val top1 = (startIdx + i * 2).toShort()
      val bot1 = (top1 + 1).toShort()
      val top2 = (top1 + 2).toShort()
      val bot2 = (top1 + 3).toShort()

      inds.addAll(listOf(top1, bot1, top2, bot1, bot2, top2))
    }
  }

  private fun addTorus(
    verts: MutableList<Float>,
    norms: MutableList<Float>,
    inds: MutableList<Short>,
    cx: Float, cy: Float, cz: Float,
    mainRadius: Float, tubeRadius: Float,
    mainSegments: Int, tubeSegments: Int
  ) {
    val startIdx = (verts.size / 3).toShort()
    for (i in 0..mainSegments) {
      val u = 2.0 * Math.PI * i.toDouble() / mainSegments.toDouble()
      val cosU = cos(u).toFloat(); val sinU = sin(u).toFloat()

      for (j in 0..tubeSegments) {
        val v = 2.0 * Math.PI * j.toDouble() / tubeSegments.toDouble()
        val cosV = cos(v).toFloat(); val sinV = sin(v).toFloat()

        val x = (mainRadius + tubeRadius * cosV) * cosU
        val y = tubeRadius * sinV
        val z = (mainRadius + tubeRadius * cosV) * sinU

        val nx = cosV * cosU
        val ny = sinV
        val nz = cosV * sinU

        verts.addAll(listOf(cx + x, cy + y, cz + z))
        norms.addAll(listOf(nx, ny, nz))
      }
    }

    for (i in 0 until mainSegments) {
      for (j in 0 until tubeSegments) {
        val first = (startIdx + i * (tubeSegments + 1) + j).toShort()
        val second = (first + tubeSegments + 1).toShort()

        inds.addAll(listOf(first, second, (first + 1).toShort()))
        inds.addAll(listOf(second, (second + 1).toShort(), (first + 1).toShort()))
      }
    }
  }
}
