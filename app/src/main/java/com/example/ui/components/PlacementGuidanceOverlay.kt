package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DisplayMode
import com.example.engine.TelemetryState

/**
 * Visual Placement Guidance UX Overlay.
 * Guides users through physical surface discovery, multi-tier hit placement (Planes, Depth, Instant Placement),
 * and 3-axis manipulation gestures (Yaw, Pitch, and 3-finger Roll/Z-axis).
 */
@Composable
fun PlacementGuidanceOverlay(
  displayMode: DisplayMode,
  telemetry: TelemetryState,
  hasPlacedAnchor: Boolean,
  modifier: Modifier = Modifier
) {
  if (displayMode == DisplayMode.OBJECT) return

  val hasSurfaces = telemetry.horizontalPlanesCount > 0 || telemetry.verticalPlanesCount > 0 || telemetry.isDepthAvailable

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp)
      .testTag("placement_guidance_overlay"),
    contentAlignment = Alignment.Center
  ) {
    AnimatedVisibility(
      visible = !hasPlacedAnchor,
      enter = fadeIn() + slideInVertically(initialOffsetY = { 20 }),
      exit = fadeOut() + slideOutVertically(targetOffsetY = { 20 })
    ) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(20.dp))
          .background(Color(0xCC0F172A))
          .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color(0xFF38BDF8).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (hasSurfaces) Icons.Default.TouchApp else Icons.Default.CropFree,
              contentDescription = "Placement Guidance",
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(20.dp)
            )
          }

          Column {
            Text(
              text = if (hasSurfaces) "Surface Ready for Placement" else "Scanning for Surfaces...",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            Text(
              text = if (hasSurfaces) {
                "Long press on floor, table, or wall to pin 3D model"
              } else {
                "Move your device slowly around the room to detect planes"
              },
              fontSize = 11.sp,
              color = Color(0xFF94A3B8)
            )
          }
        }
      }
    }

    AnimatedVisibility(
      visible = hasPlacedAnchor,
      enter = fadeIn(),
      exit = fadeOut()
    ) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(16.dp))
          .background(Color(0xAA0F172A))
          .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
          .padding(horizontal = 12.dp, vertical = 6.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = Icons.Default.ScreenRotation,
            contentDescription = "Gestures",
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(14.dp)
          )
          Text(
            text = "1-Finger: Pan • 2-Finger: Rotate/Scale • 3-Finger: Roll (Z-axis)",
            fontSize = 10.sp,
            color = Color(0xFFE2E8F0)
          )
        }
      }
    }
  }
}
