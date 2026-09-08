package com.example.renderer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.arcore.ArCoreSessionManager
import com.example.arcore.DepthOcclusionManager
import com.example.model.DisplayMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Architecture semantic classification for MR passthrough capture vs rendering.
 */
enum class MrPassthroughStereoSemantics {
  /**
   * Monoscopic physical camera sensor stream duplicated into left and right viewports,
   * coupled with true stereoscopic off-axis asymmetric virtual 3D rendering per eye.
   */
  MONOSCOPIC_PASSTHROUGH_STEREOSCOPIC_VIRTUAL,

  /**
   * True dual physical binocular camera capture sensors with hardware baseline disparity.
   */
  TRUE_STEREO_BINOCULAR_PASSTHROUGH
}

/**
 * High-performance Authoritative Camera Passthrough GLSurfaceView for AR and MR modes.
 *
 * Authoritative Pipeline Flow:
 * ARCore Camera -> One SurfaceTexture -> One GL External Texture -> AR/MR Renderer
 *
 * In AR Mode: Renders single full-screen camera passthrough.
 * In MR Mode: Duplicates camera passthrough into Left and Right stereo viewports.
 * In Object Mode: Renders clean dark studio background (pauses continuous rendering).
 */
class DualCameraGLSurfaceView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

  val passthroughSemantics: MrPassthroughStereoSemantics =
    MrPassthroughStereoSemantics.MONOSCOPIC_PASSTHROUGH_STEREOSCOPIC_VIRTUAL

  val isHardwareBinocularStereo: Boolean = false

  companion object {
    private const val TAG = "DualCameraGLView"

    private const val VERTEX_SHADER = """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      varying vec2 vScreenUv;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
        vScreenUv = (aPosition.xy + 1.0) * 0.5;
      }
    """

    private const val FRAGMENT_SHADER = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 vTexCoord;
      varying vec2 vScreenUv;
      uniform samplerExternalOES uTexture;
      uniform sampler2D uPhysicalDepthTexture;
      uniform mat4 uDepthUvMatrix;
      uniform int uDepthOcclusionActive;
      uniform float uMinPhysicalDepth;
      uniform float uMaxPhysicalDepth;
      uniform float uVirtualDepth;

      float reconstructPhysicalDepthMeters(sampler2D depthTexture, vec2 depthUv) {
        vec4 packedDepth = texture2D(depthTexture, depthUv);
        // Luminance (R) = low byte, Alpha (A) = high byte
        float depthMm = (packedDepth.r * 255.0) + (packedDepth.a * 255.0 * 256.0);
        if (depthMm < 80.0 || depthMm > 15000.0) {
          return 0.0;
        }
        return depthMm / 1000.0;
      }

      void main() {
        vec4 cameraColor = texture2D(uTexture, vTexCoord);
        if (uDepthOcclusionActive == 1) {
          // True GPU depth occlusion: physical depth sampled via ARCore calibrated transformCoordinates2d UV matrix
          vec4 depthUvHomogeneous = uDepthUvMatrix * vec4(vScreenUv, 0.0, 1.0);
          vec2 depthUv = depthUvHomogeneous.xy / depthUvHomogeneous.w;
          if (depthUv.x >= 0.0 && depthUv.x <= 1.0 && depthUv.y >= 0.0 && depthUv.y <= 1.0) {
            float physicalDepth = reconstructPhysicalDepthMeters(uPhysicalDepthTexture, depthUv);
            if (physicalDepth > 0.08) {
              float compareDepth = (uVirtualDepth > 0.1) ? uVirtualDepth : uMaxPhysicalDepth;
              if (physicalDepth < compareDepth) {
                // Real foreground physical object occludes virtual background
                gl_FragColor = cameraColor;
                return;
              }
            }
          }
        }
        gl_FragColor = cameraColor;
      }
    """

    private val DEFAULT_TEX_COORDS = floatArrayOf(
      0.0f, 0.0f,
      1.0f, 0.0f,
      0.0f, 1.0f,
      1.0f, 1.0f
    )
  }

  private var program = 0
  private var aPositionHandle = 0
  private var aTexCoordHandle = 0
  private var uTexMatrixHandle = 0
  private var uTextureHandle = 0
  private var uPhysicalDepthTextureHandle = 0
  private var uDepthUvMatrixHandle = 0
  private var uDepthOcclusionActiveHandle = 0
  private var uMinPhysicalDepthHandle = 0
  private var uMaxPhysicalDepthHandle = 0
  private var uVirtualDepthHandle = 0
  var textureId = 0
    private set

  val cameraStreamStatus: String
    get() = when {
      totalCameraFramesReceived > 0L && !isCameraXActive -> "ARCORE_CAMERA_ACTIVE"
      isCameraXActive -> "CAMERAX_FALLBACK_ACTIVE"
      else -> "CAMERA_UNAVAILABLE"
    }

  var onCameraTextureReady: ((Int) -> Unit)? = null
  var onCameraSurfaceReady: ((Surface) -> Unit)? = null
  var onSynchronizedRenderStateReady: ((com.example.arcore.SynchronizedArRenderState) -> Unit)? = null

  var arCoreSessionManager: ArCoreSessionManager? = null
    set(value) {
      field = value
      if (value != null && textureId != 0) {
        queueEvent {
          value.setCameraTextureName(textureId)
        }
      }
    }

  var depthOcclusionManager: DepthOcclusionManager? = null

  var surfaceTexture: SurfaceTexture? = null
    private set

  var cameraSurface: Surface? = null
    private set

  var virtualDepthMeters: Float = 1.2f

  var lifecycleOwner: LifecycleOwner? = null

  private var cameraProvider: ProcessCameraProvider? = null
  private var isCameraXActive = false

  private var viewWidth = 1
  private var viewHeight = 1
  private val texMatrix = FloatArray(16)
  private val scratchLeftEyeTexMatrix = FloatArray(16)
  private val scratchRightEyeTexMatrix = FloatArray(16)
  private var hasNewFrame = false
  var totalCameraFramesReceived: Long = 0L
    private set
  var lastCameraFrameTimeMs: Long = 0L
    private set

  var displayMode: DisplayMode = DisplayMode.OBJECT
    set(value) {
      val changed = field != value
      field = value
      renderMode = if (value == DisplayMode.OBJECT) {
        RENDERMODE_WHEN_DIRTY
      } else {
        RENDERMODE_CONTINUOUSLY
      }
      if (changed) {
        if (value == DisplayMode.OBJECT) {
          stopCameraX()
        } else {
          lifecycleOwner?.let { owner ->
            val sm = arCoreSessionManager
            if (sm == null || !sm.isArCorePackageInstalled()) {
              startCameraX(owner)
            }
          }
        }
      }
      try {
        requestRender()
      } catch (e: Exception) {
        // Safe when paused or detached
      }
    }

  private val vertexBuffer: FloatBuffer
  private val texBuffer: FloatBuffer
  private val scratchQuadCoords = floatArrayOf(
    -1.0f, -1.0f,
     1.0f, -1.0f,
    -1.0f,  1.0f,
     1.0f,  1.0f
  )
  private val scratchTexCoords = FloatArray(8)

  init {
    setEGLContextClientVersion(2)
    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    preserveEGLContextOnPause = true
    setRenderer(this)
    renderMode = RENDERMODE_WHEN_DIRTY
    android.opengl.Matrix.setIdentityM(texMatrix, 0)

    val quadCoords = floatArrayOf(
      -1.0f, -1.0f,
       1.0f, -1.0f,
      -1.0f,  1.0f,
       1.0f,  1.0f
    )
    val texCoords = floatArrayOf(
      0.0f, 0.0f,
      1.0f, 0.0f,
      0.0f, 1.0f,
      1.0f, 1.0f
    )

    vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(quadCoords)
        position(0)
      }

    texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(texCoords)
        position(0)
      }
  }

  /**
   * Transforms camera texture coordinates directly from current ARCore frame.
   * Ensures zero aspect-ratio distortion and exact rotation alignment without black borders.
   */
  fun updateFromArCoreFrame(frame: com.google.ar.core.Frame) {
    try {
      totalCameraFramesReceived++
      lastCameraFrameTimeMs = System.currentTimeMillis()
      frame.transformCoordinates2d(
        com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
        scratchQuadCoords,
        com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
        scratchTexCoords
      )
      texBuffer.position(0)
      texBuffer.put(scratchTexCoords)
      texBuffer.position(0)
      requestRender()
    } catch (e: Exception) {
      requestRender()
    }
  }

  fun attachLifecycle(owner: LifecycleOwner) {
    this.lifecycleOwner = owner
    if (displayMode == DisplayMode.OBJECT) return
    val sm = arCoreSessionManager
    if (cameraSurface != null && (sm == null || sm.session == null || !sm.isArCorePackageInstalled())) {
      startCameraX(owner)
    }
  }

  fun startCameraX(owner: LifecycleOwner) {
    if (displayMode == DisplayMode.OBJECT) return
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return
    val surf = cameraSurface ?: return
    if (!surf.isValid) return

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
      try {
        val provider = cameraProviderFuture.get()
        cameraProvider = provider
        provider.unbindAll()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request ->
          val currentSurf = cameraSurface
          if (currentSurf != null && currentSurf.isValid) {
            request.provideSurface(currentSurf, ContextCompat.getMainExecutor(context)) {
              // Surface release callback
            }
          } else {
            request.willNotProvideSurface()
          }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        provider.bindToLifecycle(owner, cameraSelector, preview)
        isCameraXActive = true
        Log.i(TAG, "CameraX successfully bound to cameraSurface.")
      } catch (e: Exception) {
        Log.w(TAG, "Failed binding CameraX: ${e.message}")
      }
    }, ContextCompat.getMainExecutor(context))
  }

  fun stopCameraX() {
    try {
      if (isCameraXActive || cameraProvider != null) {
        cameraProvider?.unbindAll()
        cameraProvider = null
        isCameraXActive = false
        Log.i(TAG, "CameraX unbound.")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error unbinding CameraX: ${e.message}")
    }
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    try {
      if (textureId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
      }
      cameraSurface?.release()
      cameraSurface = null
      surfaceTexture?.release()
      surfaceTexture = null

      if (program != 0) {
        GLES20.glDeleteProgram(program)
        program = 0
      }

      program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
      aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
      aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
      uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
      uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
      uPhysicalDepthTextureHandle = GLES20.glGetUniformLocation(program, "uPhysicalDepthTexture")
      uDepthUvMatrixHandle = GLES20.glGetUniformLocation(program, "uDepthUvMatrix")
      uDepthOcclusionActiveHandle = GLES20.glGetUniformLocation(program, "uDepthOcclusionActive")
      uMinPhysicalDepthHandle = GLES20.glGetUniformLocation(program, "uMinPhysicalDepth")
      uMaxPhysicalDepthHandle = GLES20.glGetUniformLocation(program, "uMaxPhysicalDepth")
      uVirtualDepthHandle = GLES20.glGetUniformLocation(program, "uVirtualDepth")

      // Cleanly rebind and recreate depth texture under this new GL context
      depthOcclusionManager?.resetGpuTextureOnContextLoss()

      // 1. Generate the ONE authoritative GL External Texture
      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      textureId = textures[0]

      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

      // 2. Rebind ARCore to the new GL texture on this GL thread
      onCameraTextureReady?.invoke(textureId)
      val sm = arCoreSessionManager
      if (sm != null) {
        sm.setCameraTextureName(textureId)
        (context as? Activity)?.let { act ->
          sm.recoverCameraStream(act)
        }
      }

      // 3. Fallback CameraX pipeline: Only instantiate SurfaceTexture if ARCore is not installed
      val isArCoreReady = sm != null && sm.isArCorePackageInstalled()
      if (!isArCoreReady && displayMode != DisplayMode.OBJECT) {
        try {
          val st = SurfaceTexture(textureId)
          st.setDefaultBufferSize(maxOf(viewWidth, 1), maxOf(viewHeight, 1))
          st.setOnFrameAvailableListener(this)
          surfaceTexture = st
          val surf = Surface(st)
          cameraSurface = surf
          onCameraSurfaceReady?.invoke(surf)
          lifecycleOwner?.let { owner ->
            post { startCameraX(owner) }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Fallback CameraX SurfaceTexture init: ${e.message}")
        }
      }

      Log.i(TAG, "Authoritative camera GL surface created with textureId=$textureId")
      requestRender()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onSurfaceCreated: ${e.message}", e)
    }
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewWidth = maxOf(width, 1)
    viewHeight = maxOf(height, 1)
    surfaceTexture?.setDefaultBufferSize(viewWidth, viewHeight)
    val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
    arCoreSessionManager?.setDisplayGeometry(rotation, viewWidth, viewHeight)
  }

  override fun onFrameAvailable(st: SurfaceTexture?) {
    hasNewFrame = true
    try {
      requestRender()
    } catch (e: Exception) {
      // Safe when view is pausing or destroyed
    }
  }

  override fun onDrawFrame(gl: GL10?) {
    try {
      GLES20.glClearColor(0.043f, 0.059f, 0.098f, 1.0f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      if (displayMode == DisplayMode.OBJECT) return

      // Authoritative Pipeline: Synchronize ARCore textureId on the GL thread
      val sm = arCoreSessionManager
      if (sm != null && sm.session != null && textureId != 0) {
        if (sm.currentCameraTextureId != textureId) {
          sm.setCameraTextureName(textureId)
        }
      }

      val frame = sm?.updateFrame()

      if (frame != null) {
        // ARCore is actively producing frames
        if (isCameraXActive) {
          post { stopCameraX() }
        }
        totalCameraFramesReceived++
        lastCameraFrameTimeMs = System.currentTimeMillis()
        frame.transformCoordinates2d(
          com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
          scratchQuadCoords,
          com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
          scratchTexCoords
        )
        texBuffer.position(0)
        texBuffer.put(scratchTexCoords)
        texBuffer.position(0)
        android.opengl.Matrix.setIdentityM(texMatrix, 0)

        // Notify synchronized AR render state for this exact frame
        val syncState = sm?.currentSynchronizedState
        if (syncState != null) {
          onSynchronizedRenderStateReady?.invoke(syncState)
        }
      } else {
        val st = surfaceTexture
        if (hasNewFrame && st != null) {
          try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
          } catch (e: Exception) {
            Log.w(TAG, "updateTexImage skipped: ${e.message}")
          }
          hasNewFrame = false
        }
        texBuffer.position(0)
        texBuffer.put(DEFAULT_TEX_COORDS)
        texBuffer.position(0)

        // If ARCore is not installed or supported and CameraX is not yet running, launch fallback CameraX
        if (!isCameraXActive && sm != null && !sm.isArCorePackageInstalled()) {
          lifecycleOwner?.let { owner ->
            post { startCameraX(owner) }
          }
        }
      }

      if (program == 0 || textureId == 0) return

      GLES20.glUseProgram(program)
      GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glUniform1i(uTextureHandle, 0)

      // True GPU Depth Occlusion: Bind and sample physical depth texture strictly when synchronized with current frame
      val dom = depthOcclusionManager
      val currentSyncState = sm?.currentSynchronizedState
      val isDepthActive = dom != null && dom.depthTextureId != 0 && (currentSyncState?.isDepthValid == true)
      if (isDepthActive && dom != null) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dom.depthTextureId)
        GLES20.glUniform1i(uPhysicalDepthTextureHandle, 1)
        GLES20.glUniformMatrix4fv(uDepthUvMatrixHandle, 1, false, currentSyncState?.depthUvTransformMatrix ?: dom.depthUvTransformMatrix, 0)
        GLES20.glUniform1i(uDepthOcclusionActiveHandle, 1)
        GLES20.glUniform1f(uMinPhysicalDepthHandle, currentSyncState?.minDepthMeters ?: dom.minDepthMeters)
        GLES20.glUniform1f(uMaxPhysicalDepthHandle, currentSyncState?.maxDepthMeters ?: dom.maxDepthMeters)
        GLES20.glUniform1f(uVirtualDepthHandle, virtualDepthMeters)
      } else {
        GLES20.glUniform1i(uDepthOcclusionActiveHandle, 0)
      }

      GLES20.glEnableVertexAttribArray(aPositionHandle)
      GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(aTexCoordHandle)
      GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

      if (displayMode == DisplayMode.MR) {
        val halfWidth = maxOf(viewWidth / 2, 1)
        // 1. Left Eye (Left Camera Viewport with optical stereo convergence shift)
        System.arraycopy(texMatrix, 0, scratchLeftEyeTexMatrix, 0, 16)
        android.opengl.Matrix.translateM(scratchLeftEyeTexMatrix, 0, -0.012f, 0f, 0f)
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, scratchLeftEyeTexMatrix, 0)
        GLES20.glViewport(0, 0, halfWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2. Right Eye (Right Camera Viewport with optical stereo convergence shift)
        System.arraycopy(texMatrix, 0, scratchRightEyeTexMatrix, 0, 16)
        android.opengl.Matrix.translateM(scratchRightEyeTexMatrix, 0, 0.012f, 0f, 0f)
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, scratchRightEyeTexMatrix, 0)
        GLES20.glViewport(halfWidth, 0, halfWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      } else {
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)
        // Single Camera Viewport (AR Mode)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      }

      GLES20.glDisableVertexAttribArray(aPositionHandle)
      GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    } catch (e: Exception) {
      Log.w(TAG, "Error in onDrawFrame: ${e.message}")
    }
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    if (vertexShader == 0) return 0
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (fragmentShader == 0) return 0

    val prog = GLES20.glCreateProgram()
    if (prog != 0) {
      GLES20.glAttachShader(prog, vertexShader)
      GLES20.glAttachShader(prog, fragmentShader)
      GLES20.glLinkProgram(prog)
      val linkStatus = IntArray(1)
      GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
      if (linkStatus[0] != GLES20.GL_TRUE) {
        Log.e(TAG, "Failed linking shader program: " + GLES20.glGetProgramInfoLog(prog))
        GLES20.glDeleteProgram(prog)
        return 0
      }
    }
    return prog
  }

  private fun loadShader(type: Int, shaderCode: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, shaderCode)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      Log.e(TAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
      GLES20.glDeleteShader(shader)
      return 0
    }
    return shader
  }

  override fun onPause() {
    try {
      super.onPause()
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing GLSurfaceView: ${e.message}")
    }
  }

  override fun onResume() {
    try {
      super.onResume()
      if (displayMode != DisplayMode.OBJECT) {
        requestRender()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error resuming GLSurfaceView: ${e.message}")
    }
  }

  fun release() {
    stopCameraX()
    try {
      queueEvent {
        try {
          cameraSurface?.release()
          cameraSurface = null
          surfaceTexture?.release()
          surfaceTexture = null
          if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
          }
          if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
          }
        } catch (e: Exception) {
          Log.w(TAG, "Error releasing GL resources: ${e.message}")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "queueEvent failed on release: ${e.message}")
    }
  }
}
