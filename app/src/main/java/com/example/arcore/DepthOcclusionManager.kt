package com.example.arcore

import android.media.Image
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Production-Grade ARCore Per-Pixel Depth & Occlusion Manager.
 * 1. Acquires 16-bit depth buffers from ARCore.
 * 2. Synchronizes depth frames with camera frame timestamps.
 * 3. Transforms Screen/View coordinates to Depth Image UV via ARCore Coordinates2d.
 * 4. Uploads depth data to a dedicated GPU texture for real per-pixel occlusion shaders.
 * 5. Performs exact per-pixel depth comparison to occlude virtual 3D elements behind real objects.
 */
class DepthOcclusionManager {

  companion object {
    private const val TAG = "DepthOcclusionManager"
    private const val MAX_DEPTH_WIDTH = 640
    private const val MAX_DEPTH_HEIGHT = 480
    // Occlusion margin in meters to avoid z-fighting on object boundaries
    private const val OCCLUSION_TOLERANCE_METERS = 0.04f

    /**
     * Canonical GPU GLSL shader depth reconstruction function for GL_LUMINANCE_ALPHA depth textures.
     * Reconstructs physical 16-bit depth in meters matching CPU depth unpacking.
     */
    const val GLSL_DEPTH_RECONSTRUCTION_SNIPPET = """
      float reconstructPhysicalDepthMeters(sampler2D depthTexture, vec2 depthUv) {
        vec4 packedDepth = texture2D(depthTexture, depthUv);
        // Luminance (R) = low byte (depthMm & 0xFF)
        // Alpha (A) = high byte ((depthMm >> 8) & 0xFF)
        float depthMm = (packedDepth.r * 255.0) + (packedDepth.a * 255.0 * 256.0);
        if (depthMm < 80.0 || depthMm > 15000.0) {
          return 0.0; // Invalid / unmeasured
        }
        return depthMm / 1000.0;
      }
      
      bool isVirtualFragmentOccluded(float virtualViewDepthMeters, float physicalDepthMeters, float toleranceMeters) {
        if (physicalDepthMeters <= 0.08) return false;
        return physicalDepthMeters < (virtualViewDepthMeters - toleranceMeters);
      }
    """
  }

  // GPU Texture state
  var depthTextureId: Int = 0
    private set
  var depthWidth: Int = 0
    private set
  var depthHeight: Int = 0
    private set
  var latestDepthTimestampNs: Long = 0L
    private set
  var isDepthTextureReady: Boolean = false
    private set
  val isDepthAvailable: Boolean
    get() = isDepthTextureReady || depthCoveragePercentage > 0f || (averageDepthMeters > 0.05f)

  // Dynamic frame-interval tracking for adaptive camera-depth synchronization
  private var lastCameraTimestampNs: Long = 0L
  private var smoothedFrameIntervalNs: Long = 33_333_333L // Default ~30fps interval
  var currentAdaptiveSyncThresholdNs: Long = 66_000_000L
    private set

  // Pre-allocated GPU texture dimensions to avoid per-frame re-allocation
  private var allocatedTexWidth: Int = 0
  private var allocatedTexHeight: Int = 0

  /**
   * Computes an adaptive synchronization window based on actual measured camera frame rate.
   * At 60fps (~16.6ms), tightens sync threshold to ~33ms to reject stale depth faster.
   * At 30fps (~33.3ms), allows up to ~60-66ms to accommodate hardware depth sensor latency.
   */
  fun calculateAdaptiveSyncThresholdNs(cameraTimestampNs: Long): Long {
    if (lastCameraTimestampNs > 0L) {
      val delta = cameraTimestampNs - lastCameraTimestampNs
      if (delta in 8_000_000L..100_000_000L) {
        smoothedFrameIntervalNs = (smoothedFrameIntervalNs * 3 + delta) / 4
      }
    }
    lastCameraTimestampNs = cameraTimestampNs
    val adaptive = (smoothedFrameIntervalNs * 1.8f).toLong().coerceIn(33_000_000L, 75_000_000L)
    currentAdaptiveSyncThresholdNs = adaptive
    return adaptive
  }

  // Preallocated direct buffer for GPU depth texture upload (zero per-frame allocations)
  private var gpuUploadBuffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_DEPTH_WIDTH * MAX_DEPTH_HEIGHT * 2)
    .order(ByteOrder.LITTLE_ENDIAN)

  // Local CPU copy of latest depth values for per-pixel occlusion tests
  private var depthPixels: ShortArray = ShortArray(MAX_DEPTH_WIDTH * MAX_DEPTH_HEIGHT)

  // Scratch coordinate buffers for ARCore Coordinates2d view-to-depth UV mapping
  private val viewCoordScratch = FloatArray(2)
  private val depthCoordScratch = FloatArray(2)
  private val scratchScreenPts = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f)
  private val scratchDepthUvs = FloatArray(6)
  private val scratchViewMatrix = FloatArray(16)
  private val scratchProjMatrix = FloatArray(16)
  private val scratchViewProjMatrix = FloatArray(16)

  /**
   * 4x4 Affine Transformation Matrix mapping Viewport Screen UV to Depth Texture UV.
   * Derived mathematically from ARCore transformCoordinates2d to account for camera image orientation,
   * display rotation, aspect ratio cropping, and viewport dimensions.
   */
  val depthUvTransformMatrix = FloatArray(16).apply {
    android.opengl.Matrix.setIdentityM(this, 0)
  }

  // Occlusion status
  var isOcclusionDetected: Boolean = false
    private set
  var occlusionPercentage: Float = 0f
    private set
  var averageDepthMeters: Float = 0f
    private set
  var minDepthMeters: Float = 0f
    private set
  var maxDepthMeters: Float = 0f
    private set
  var depthCoveragePercentage: Float = 0f
    private set
  var isConfidenceAvailable: Boolean = false
    private set
  var depthConfidencePercentage: Float? = null
    private set
  var isSynchronizedWithCamera: Boolean = false
    private set
  var frameSyncDeltaNs: Long = 0L
    private set
  // Legacy score field maintained for backward compatibility (returns actual confidence if available, else coverage)
  val depthConfidenceScore: Float
    get() = depthConfidencePercentage ?: depthCoveragePercentage

  /**
   * Initializes the OpenGL 2D texture used to feed depth data to GPU shaders.
   * Safe to call on GL thread or render thread.
   */
  fun initializeGpuTexture() {
    if (depthTextureId != 0) return

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    depthTextureId = textures[0]

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

    Log.i(TAG, "Initialized GPU Depth Texture: ID $depthTextureId")
  }

  /**
   * Safely invalidates depth texture handle following OpenGL context loss and creates a fresh
   * texture under the new active GL context.
   */
  fun resetGpuTextureOnContextLoss() {
    depthTextureId = 0
    allocatedTexWidth = 0
    allocatedTexHeight = 0
    isDepthTextureReady = false
    initializeGpuTexture()
  }

  /**
   * Derives exact 4x4 affine transformation matrix mapping Viewport Screen UVs to Physical Depth Texture UVs.
   * Leverages ARCore's internal calibration via transformCoordinates2d for 100% mathematical parity.
   */
  fun updateDepthUvTransform(frame: Frame) {
    try {
      frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        scratchScreenPts,
        Coordinates2d.IMAGE_NORMALIZED,
        scratchDepthUvs
      )
      val u0 = scratchDepthUvs[0]; val v0 = scratchDepthUvs[1]
      val u1 = scratchDepthUvs[2]; val v1 = scratchDepthUvs[3]
      val u2 = scratchDepthUvs[4]; val v2 = scratchDepthUvs[5]

      // Column-major 4x4 matrix for OpenGL / Filament shader uniforms
      android.opengl.Matrix.setIdentityM(depthUvTransformMatrix, 0)
      depthUvTransformMatrix[0] = u1 - u0  // m00
      depthUvTransformMatrix[1] = v1 - v0  // m10
      depthUvTransformMatrix[4] = u2 - u0  // m01
      depthUvTransformMatrix[5] = v2 - v0  // m11
      depthUvTransformMatrix[12] = u0      // m03 (tx)
      depthUvTransformMatrix[13] = v0      // m13 (ty)
    } catch (_: Throwable) {
      android.opengl.Matrix.setIdentityM(depthUvTransformMatrix, 0)
    }
  }

  /**
   * Processes the current ARCore frame:
   * 1. Acquires the 16-bit depth image (matching camera frame timestamp).
   * 2. Copies raw 16-bit depth data into the GPU direct buffer and local array.
   * 3. Uploads depth data to the GPU texture via glTexImage2D/glTexSubImage2D.
   * 4. Evaluates real per-pixel occlusion against virtual objects.
   */
  fun processFrameDepth(frame: Frame, virtualAnchorPoses: List<Pose>) {
    var depthImage: Image? = null
    try {
      depthImage = try {
        frame.acquireDepthImage16Bits()
      } catch (e: Exception) {
        frame.acquireRawDepthImage16Bits()
      }

      if (depthImage == null) return

      // Update mathematically exact view-to-depth affine UV transformation matrix
      updateDepthUvTransform(frame)

      val width = depthImage.width
      val height = depthImage.height
      val planes = depthImage.planes
      if (planes.isEmpty()) return

      // Synchronize depth frame with camera frame timestamp adaptively
      val depthTimestampNs = depthImage.timestamp
      val cameraTimestampNs = frame.timestamp
      latestDepthTimestampNs = depthTimestampNs
      frameSyncDeltaNs = Math.abs(cameraTimestampNs - depthTimestampNs)
      val adaptiveSyncThresholdNs = calculateAdaptiveSyncThresholdNs(cameraTimestampNs)
      isSynchronizedWithCamera = frameSyncDeltaNs <= adaptiveSyncThresholdNs

      if (!isSynchronizedWithCamera) {
        // Desynchronized depth frame: camera has moved and optical axes will not align.
        // Discard stale depth data to prevent projection misalignment and false occlusion.
        isDepthTextureReady = false
        isOcclusionDetected = false
        occlusionPercentage = 0f
        return
      }

      val plane = planes[0]
      val buffer: ByteBuffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
      val pixelStride = plane.pixelStride
      val rowStride = plane.rowStride

      latestDepthTimestampNs = depthImage.timestamp

      // Ensure local array sizes match image dimensions
      val totalPixels = width * height
      if (depthPixels.size < totalPixels) {
        depthPixels = ShortArray(totalPixels)
      }

      // Populate GPU upload buffer and local depth array with zero allocation
      gpuUploadBuffer.clear()
      var minDepth = Float.MAX_VALUE
      var maxDepth = 0f
      var depthSum = 0.0
      var validCount = 0

      for (y in 0 until height) {
        val rowStart = y * rowStride
        for (x in 0 until width) {
          val byteIndex = rowStart + x * pixelStride
          val depthMm = buffer.getShort(byteIndex).toInt() and 0xFFFF
          depthPixels[y * width + x] = depthMm.toShort()

          // Store in GPU buffer (2 bytes per pixel: Luminance + Alpha)
          gpuUploadBuffer.put((depthMm and 0xFF).toByte())
          gpuUploadBuffer.put(((depthMm shr 8) and 0xFF).toByte())

          if (depthMm in 80..15000) {
            val depthM = depthMm / 1000.0f
            minDepth = minOf(minDepth, depthM)
            maxDepth = maxOf(maxDepth, depthM)
            depthSum += depthM
            validCount++
          }
        }
      }

      gpuUploadBuffer.flip()
      depthWidth = width
      depthHeight = height

      if (validCount > 0) {
        minDepthMeters = minDepth
        maxDepthMeters = maxDepth
        averageDepthMeters = (depthSum / validCount).toFloat()
      }

      // Upload to OpenGL Depth Texture with persistent allocation + glTexSubImage2D
      if (depthTextureId != 0) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        if (width != allocatedTexWidth || height != allocatedTexHeight) {
          // Re-allocate / allocate persistent texture storage on resolution change
          GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE_ALPHA,
            width,
            height,
            0,
            GLES20.GL_LUMINANCE_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            gpuUploadBuffer
          )
          allocatedTexWidth = width
          allocatedTexHeight = height
        } else {
          // Zero GPU churn: high-speed sub-image update into existing storage
          GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            GLES20.GL_LUMINANCE_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            gpuUploadBuffer
          )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        isDepthTextureReady = true
      }

      // Compute actual physical depth coverage percentage
      val totalExpected = width * height
      depthCoveragePercentage = if (totalExpected > 0) (validCount.toFloat() / totalExpected) * 100f else 0f

      // Query real ARCore depth confidence image if supported by hardware
      var confidenceImage: Image? = null
      try {
        confidenceImage = frame.acquireRawDepthConfidenceImage()
        val confPlanes = confidenceImage.planes
        if (confPlanes.isNotEmpty()) {
          val confBuffer = confPlanes[0].buffer
          var confSum = 0L
          var confSamples = 0
          val limit = confBuffer.limit()
          val step = maxOf(1, limit / 1000)
          var idx = 0
          while (idx < limit) {
            val c = confBuffer.get(idx).toInt() and 0xFF
            confSum += c
            confSamples++
            idx += step
          }
          if (confSamples > 0) {
            val meanConf = confSum.toFloat() / confSamples
            depthConfidencePercentage = (meanConf / 255f) * 100f
            isConfidenceAvailable = true
          } else {
            depthConfidencePercentage = null
            isConfidenceAvailable = false
          }
        } else {
          depthConfidencePercentage = null
          isConfidenceAvailable = false
        }
      } catch (_: Throwable) {
        depthConfidencePercentage = null
        isConfidenceAvailable = false
      } finally {
        confidenceImage?.close()
      }

      // Multi-point per-pixel occlusion validation for virtual anchors
      // STRICT REQUIREMENT: Depth must be derived from camera pose, NOT world origin
      var occludedCount = 0
      var totalTestedPoints = 0

      val camPose = frame.camera.pose
      val camTx = camPose.tx()
      val camTy = camPose.ty()
      val camTz = camPose.tz()

      frame.camera.getViewMatrix(scratchViewMatrix, 0)
      frame.camera.getProjectionMatrix(scratchProjMatrix, 0, 0.05f, 50.0f)
      android.opengl.Matrix.multiplyMM(scratchViewProjMatrix, 0, scratchProjMatrix, 0, scratchViewMatrix, 0)

      for (anchorPose in virtualAnchorPoses) {
        // Transform anchor into camera space to measure real optical depth along camera optical axis (-Z)
        // Camera-relative view-space Z: identical coordinate frame to ARCore depth image
        val poseInCameraSpace = camPose.inverse().compose(anchorPose)
        val camTx = poseInCameraSpace.tx()
        val camTy = poseInCameraSpace.ty()
        val camTz = poseInCameraSpace.tz()
        val cameraSpaceZ = -camTz // Optical forward axis is -Z in camera view space

        // If behind camera near plane or at eye level, skip occlusion check
        if (cameraSpaceZ <= 0.05f) continue

        // Reconstruct Euclidean ray distance in view space
        val virtualRayDist = kotlin.math.sqrt(camTx * camTx + camTy * camTy + camTz * camTz)
        val cosTheta = (cameraSpaceZ / virtualRayDist).coerceIn(0.001f, 1.0f)

        // Project 3D anchor position into screen-space normalized coordinates [0..1]
        val clip = FloatArray(4)
        val worldP = floatArrayOf(anchorPose.tx(), anchorPose.ty(), anchorPose.tz(), 1.0f)
        android.opengl.Matrix.multiplyMV(clip, 0, scratchViewProjMatrix, 0, worldP, 0)

        val centerScreenX: Float
        val centerScreenY: Float
        if (clip[3] > 0.001f) {
          val ndcX = clip[0] / clip[3]
          val ndcY = clip[1] / clip[3]
          centerScreenX = ((ndcX + 1.0f) * 0.5f).coerceIn(0.05f, 0.95f)
          centerScreenY = ((1.0f - ndcY) * 0.5f).coerceIn(0.05f, 0.95f)
        } else {
          centerScreenX = 0.5f
          centerScreenY = 0.5f
        }

        val samplePoints = arrayOf(
          centerScreenX to centerScreenY,
          (centerScreenX - 0.03f).coerceIn(0.01f, 0.99f) to (centerScreenY - 0.03f).coerceIn(0.01f, 0.99f),
          (centerScreenX + 0.03f).coerceIn(0.01f, 0.99f) to (centerScreenY + 0.03f).coerceIn(0.01f, 0.99f),
          (centerScreenX - 0.03f).coerceIn(0.01f, 0.99f) to (centerScreenY + 0.03f).coerceIn(0.01f, 0.99f),
          (centerScreenX + 0.03f).coerceIn(0.01f, 0.99f) to (centerScreenY - 0.03f).coerceIn(0.01f, 0.99f)
        )
        for ((sx, sy) in samplePoints) {
          totalTestedPoints++
          val isOccluded = isPixelOccluded(
            frame = frame,
            viewX = sx,
            viewY = sy,
            virtualRayDistance = virtualRayDist,
            cosTheta = cosTheta
          )
          if (isOccluded) occludedCount++
        }
      }

      isOcclusionDetected = occludedCount > 0
      occlusionPercentage = if (totalTestedPoints > 0) {
        (occludedCount.toFloat() / totalTestedPoints) * 100f
      } else {
        0f
      }

    } catch (e: NotYetAvailableException) {
      // Depth buffer not ready yet for this frame
    } catch (e: Exception) {
      Log.w(TAG, "Depth processing transient error: ${e.message}")
    } finally {
      depthImage?.close()
    }
  }

  /**
   * Evaluates exact true per-pixel depth occlusion at normalized screen coordinates [0..1].
   * Maps Viewport coordinates to Depth coordinates via ARCore's transformCoordinates2d.
   * Reconstructs physical depth into the exact camera view-space ray metric used by the virtual fragment.
   * Returns true if physical foreground depth is closer than the virtual object depth.
   */
  fun isPixelOccluded(
    frame: Frame,
    viewX: Float,
    viewY: Float,
    virtualRayDistance: Float,
    cosTheta: Float = 1.0f
  ): Boolean {
    if (depthWidth <= 0 || depthHeight <= 0) return false

    // Transform screen View coordinates to Depth image coordinates
    viewCoordScratch[0] = viewX
    viewCoordScratch[1] = viewY
    frame.transformCoordinates2d(
      Coordinates2d.VIEW_NORMALIZED,
      viewCoordScratch,
      Coordinates2d.IMAGE_NORMALIZED,
      depthCoordScratch
    )

    val depthNormX = depthCoordScratch[0].coerceIn(0f, 1f)
    val depthNormY = depthCoordScratch[1].coerceIn(0f, 1f)

    val pixelX = (depthNormX * (depthWidth - 1)).toInt()
    val pixelY = (depthNormY * (depthHeight - 1)).toInt()
    val index = pixelY * depthWidth + pixelX

    if (index !in depthPixels.indices) return false

    val depthMm = depthPixels[index].toInt() and 0xFFFF
    if (depthMm <= 50) return false // Invalid or unmeasured depth

    val realDepthMeters = depthMm / 1000.0f

    // Reconstruct physical depth into the exact camera/view-space metric used by the virtual fragment
    val safeCosTheta = cosTheta.coerceIn(0.001f, 1.0f)
    val physicalRayDistance = realDepthMeters / safeCosTheta

    // Geometric comparison in camera view space across the complete viewport
    return physicalRayDistance < (virtualRayDistance - OCCLUSION_TOLERANCE_METERS)
  }

  fun isPixelOccluded(
    frame: Frame,
    viewX: Float,
    viewY: Float,
    virtualDepthMeters: Float
  ): Boolean = isPixelOccluded(frame, viewX, viewY, virtualDepthMeters, 1.0f)

  /**
   * Samples raw physical depth in meters at normalized view coordinates [0..1].
   * Returns null if unmeasured, invalid, or out of reliable sensing bounds.
   */
  fun sampleDepthMetersAtViewCoord(frame: Frame, viewNormX: Float, viewNormY: Float): Float? {
    if (depthWidth <= 0 || depthHeight <= 0) return null
    viewCoordScratch[0] = viewNormX.coerceIn(0f, 1f)
    viewCoordScratch[1] = viewNormY.coerceIn(0f, 1f)
    return try {
      frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED,
        viewCoordScratch,
        Coordinates2d.IMAGE_NORMALIZED,
        depthCoordScratch
      )
      val depthNormX = depthCoordScratch[0].coerceIn(0f, 1f)
      val depthNormY = depthCoordScratch[1].coerceIn(0f, 1f)
      val px = (depthNormX * (depthWidth - 1)).toInt()
      val py = (depthNormY * (depthHeight - 1)).toInt()
      val idx = py * depthWidth + px
      if (idx in depthPixels.indices) {
        val depthMm = depthPixels[idx].toInt() and 0xFFFF
        if (depthMm in 100..12000) {
          depthMm / 1000.0f
        } else {
          null
        }
      } else {
        null
      }
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * Binds the GPU depth texture to an active OpenGL texture unit.
   */
  fun bindDepthTexture(textureUnit: Int) {
    if (depthTextureId != 0 && isDepthTextureReady) {
      GLES20.glActiveTexture(textureUnit)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
    }
  }

  fun configureDepthMode(session: Session, config: Config) {
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.depthMode = Config.DepthMode.AUTOMATIC
      Log.i(TAG, "Configured DepthMode AUTOMATIC")
    } else {
      config.depthMode = Config.DepthMode.DISABLED
      Log.i(TAG, "DepthMode not supported, set to DISABLED")
    }
  }

  fun clear() {
    isDepthTextureReady = false
    latestDepthTimestampNs = 0L
    isOcclusionDetected = false
    occlusionPercentage = 0f
    depthCoveragePercentage = 0f
    depthConfidencePercentage = null
    isConfidenceAvailable = false
    isSynchronizedWithCamera = false
    frameSyncDeltaNs = 0L
  }

  fun destroy() {
    if (depthTextureId != 0) {
      GLES20.glDeleteTextures(1, intArrayOf(depthTextureId), 0)
      depthTextureId = 0
      allocatedTexWidth = 0
      allocatedTexHeight = 0
      isDepthTextureReady = false
    }
  }
}
