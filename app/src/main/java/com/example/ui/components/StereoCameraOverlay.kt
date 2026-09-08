package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Clean Stereoscopic MR Viewport Divider.
 * Separates the screen cleanly into Left Eye and Right Eye viewports for MR/VR headsets
 * without cluttering badges, text, or distracting blue overlays.
 */
@Composable
fun StereoCameraOverlay(
  ipdMm: Float = 64f,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .testTag("stereo_camera_overlay")
  ) {
    // Subtle Vertical Center Divider Line
    Box(
      modifier = Modifier
        .width(2.dp)
        .fillMaxHeight()
        .align(Alignment.Center)
        .background(Color.Black.copy(alpha = 0.85f))
    )
  }
}
