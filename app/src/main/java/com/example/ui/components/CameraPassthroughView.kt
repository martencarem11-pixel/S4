package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.DisplayMode
import com.example.renderer.DualCameraGLSurfaceView

/**
 * CameraPassthroughView provides real-time, hardware-accelerated camera feed.
 * Authoritative pipeline: ARCore Camera -> One SurfaceTexture -> One GL External Texture -> AR/MR Renderer
 * - In MR Mode: Doubles the camera into Left and Right stereo viewports.
 * - In AR Mode: Renders single full-screen camera passthrough.
 * - In Object Mode: Clean dark studio canvas.
 */
@Composable
fun CameraPassthroughView(
  displayMode: DisplayMode,
  hasCameraPermission: Boolean,
  onRequestPermission: () -> Unit,
  onDualCameraCreated: ((DualCameraGLSurfaceView) -> Unit)? = null,
  isArCoreActive: Boolean = false,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val dualCameraView = remember {
    DualCameraGLSurfaceView(context).also {
      it.displayMode = displayMode
      it.attachLifecycle(lifecycleOwner)
      onDualCameraCreated?.invoke(it)
    }
  }

  LaunchedEffect(displayMode) {
    dualCameraView.displayMode = displayMode
  }

  DisposableEffect(lifecycleOwner, dualCameraView) {
    dualCameraView.attachLifecycle(lifecycleOwner)
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> dualCameraView.onResume()
        Lifecycle.Event.ON_PAUSE -> dualCameraView.onPause()
        Lifecycle.Event.ON_DESTROY -> dualCameraView.release()
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      dualCameraView.release()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF070B14))
      .testTag("camera_passthrough_view")
  ) {
    // 1. Authoritative Dual Camera Viewport - persistent across mode switches
    AndroidView(
      factory = { dualCameraView },
      modifier = Modifier.fillMaxSize()
    )

    // 2. Permission Request Card - shown in AR/MR modes when camera permission is missing
    if (!hasCameraPermission && displayMode != DisplayMode.OBJECT) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0F172A))
          .padding(32.dp)
          .testTag("camera_permission_card")
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(56.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Camera Access Required",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Enable camera permissions to view 3D exhibits placed in your physical environment.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("grant_camera_permission_button")
          ) {
            Text("Allow Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}
