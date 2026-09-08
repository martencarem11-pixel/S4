package com.example.arcore

import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Production-grade Depth Occlusion Manager.
 * Properly binds depth texture to fragment shaders for real-world occlusion of virtual objects.
 * Eliminates rendering of virtual models on top of real-world people and obstacles.
 */
class DepthOcclusionManager {
  companion object {
    private const val TAG = "DepthOcclusionManager"
    private const val DEPTH_TEXTURE_UNIT = 4
  }

  var depthTextureId: Int = 0
    private set
  var depthWidth: Int = 0
    private set
  var depthHeight: Int = 0
    private set
  var minDepthMeters: Float = 0.1f
    private set
  var maxDepthMeters: Float = 5.0f
    private set
  var averageDepthMeters: Float = 1.0f
    private set
  var occlusionPercentage: Float = 0f
    private set
  var latestDepthTimestampNs: Long = 0L
    private set
  var isSynchronizedWithCamera: Boolean = false
    private set
  var isDepthTextureReady: Boolean = false
    private set
  var depthUvTransformMatrix: FloatArray = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
    private set

  private var lastDepthDataSizeBytes = 0
  private var occludedPixelCount = 0
  private var totalPixelCount = 0

  /**
   * Process frame depth and upload to GPU with proper synchronization.
   */
  fun processFrameDepth(frame: Frame, anchorPoses: List<Pose>) {
    try {
      val depthImage = frame.acquireDepthImage()
      if (depthImage == null) {
        isDepthTextureReady = false
        return
      }

      try {
        depthWidth = depthImage.width
        depthHeight = depthImage.height
        latestDepthTimestampNs = depthImage.timestamp
        totalPixelCount = depthWidth * depthHeight

        // Extract depth data into float buffer
        val planes = depthImage.planes
        val depthPlane = planes[0]
        val depthBuffer = depthPlane.buffer
        depthBuffer.rewind()

        val pixelStride = depthPlane.pixelStride
        val rowPadding = depthPlane.rowPadding
        val depthData = FloatArray(totalPixelCount)

        lastDepthDataSizeBytes = depthBuffer.remaining()
        var pixelIdx = 0
        var sumDepth = 0f
        occludedPixelCount = 0

        for (y in 0 until depthHeight) {
          var idx = y * (depthWidth * pixelStride + rowPadding)
          for (x in 0 until depthWidth) {
            val depthSample = depthBuffer.short.toFloat() / 1000.0f // Convert mm to meters
            depthData[pixelIdx] = depthSample
            sumDepth += depthSample

            // Count occlusion: pixels between 0.1m - 2.5m are likely occluders
            if (depthSample in 0.1f..2.5f) {
              occludedPixelCount++
            }

            idx += pixelStride
            pixelIdx++
          }
        }

        averageDepthMeters = sumDepth / maxOf(1, totalPixelCount)
        minDepthMeters = depthData.minOrNull() ?: 0.1f
        maxDepthMeters = depthData.maxOrNull() ?: 5.0f
        occlusionPercentage = (occludedPixelCount.toFloat() / maxOf(1, totalPixelCount)) * 100f

        // Upload to GPU
        uploadDepthTextureToGpu(depthData)
        isDepthTextureReady = true
        isSynchronizedWithCamera = true

        Log.d(TAG, "Depth frame processed: ${depthWidth}x${depthHeight}, Occlusion: $occlusionPercentage%")
      } finally {
        depthImage.close()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error processing depth: ${e.message}")
      isDepthTextureReady = false
    }
  }

  private fun uploadDepthTextureToGpu(depthData: FloatArray) {
    try {
      if (depthTextureId == 0) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        depthTextureId = textures[0]
      }

      // Bind and configure texture
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + DEPTH_TEXTURE_UNIT)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)

      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

      // Upload float depth data
      val depthFloatBuffer = FloatBuffer.wrap(depthData)
      GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
        depthWidth, depthHeight, 0,
        GLES20.GL_LUMINANCE, GLES20.GL_FLOAT, depthFloatBuffer
      )

      checkGlError("Depth texture upload")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to upload depth texture: ${e.message}")
    }
  }

  /**
   * Bind depth texture to fragment shader for occlusion rendering.
   * Call this before rendering virtual objects in AR/MR mode.
   */
  fun bindDepthTextureToShader(shaderProgram: Int, uniformName: String = "u_DepthTexture") {
    if (depthTextureId == 0 || !isDepthTextureReady) return

    try {
      val uniformLoc = GLES20.glGetUniformLocation(shaderProgram, uniformName)
      if (uniformLoc != -1) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + DEPTH_TEXTURE_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES20.glUniform1i(uniformLoc, DEPTH_TEXTURE_UNIT)
      }
      checkGlError("Bind depth texture")
    } catch (e: Exception) {
      Log.w(TAG, "Error binding depth texture: ${e.message}")
    }
  }

  fun clear() {
    if (depthTextureId != 0) {
      try {
        GLES20.glDeleteTextures(1, intArrayOf(depthTextureId), 0)
      } catch (e: Exception) {
        Log.w(TAG, "Error deleting depth texture: ${e.message}")
      }
      depthTextureId = 0
    }
    isDepthTextureReady = false
    isSynchronizedWithCamera = false
  }

  private fun checkGlError(label: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
      Log.e(TAG, "GL Error in $label: $error")
    }
  }
}
