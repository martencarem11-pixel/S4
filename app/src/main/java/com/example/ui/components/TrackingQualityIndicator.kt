package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TelemetryState

/**
 * Production-grade Tracking Quality UX Indicator.
 * Informs the user in real time about 6DoF tracking fidelity, lighting, surface density,
 * and drift detection with expandable micro-telemetry.
 */
@Composable
fun TrackingQualityIndicator(
  telemetry: TelemetryState,
  modifier: Modifier = Modifier
) {
  var isExpanded by remember { mutableStateOf(false) }

  val (statusLabel, statusColor, iconVector) = when {
    telemetry.isDriftActive -> Triple(
      "Drift Frame #${telemetry.driftStartFrameIndex ?: 0}",
      Color(0xFFEF4444),
      Icons.Default.Warning
    )
    telemetry.trackingQuality == "OPTIMAL_6DOF" -> Triple(
      "6DoF Optimal",
      Color(0xFF22C55E),
      Icons.Default.CheckCircle
    )
    telemetry.trackingQuality == "LIMITED_LOW_FEATURES" -> Triple(
      "Low Features",
      Color(0xFFF59E0B),
      Icons.Default.Info
    )
    telemetry.trackingQuality == "LIMITED_FAST_MOTION" -> Triple(
      "Fast Motion",
      Color(0xFFF59E0B),
      Icons.Default.Warning
    )
    telemetry.trackingQuality == "LIMITED_LOW_LIGHT" -> Triple(
      "Low Light",
      Color(0xFFF59E0B),
      Icons.Default.Warning
    )
    telemetry.trackingQuality == "SEARCHING_SURFACES" -> Triple(
      "Scanning...",
      Color(0xFF38BDF8),
      Icons.Default.Info
    )
    else -> Triple(
      telemetry.trackingQuality.replace("_", " "),
      Color(0xFF94A3B8),
      Icons.Default.Info
    )
  }

  Box(
    modifier = modifier
      .animateContentSize()
      .clip(RoundedCornerShape(16.dp))
      .background(Color(0xCC0F172A))
      .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      .clickable { isExpanded = !isExpanded }
      .padding(horizontal = 10.dp, vertical = 6.dp)
      .testTag("tracking_quality_indicator")
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(statusColor)
        )

        Text(
          text = statusLabel,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color.White
        )

        Icon(
          imageVector = iconVector,
          contentDescription = "Tracking Quality",
          tint = statusColor,
          modifier = Modifier.size(13.dp)
        )
      }

      AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Column(
          modifier = Modifier.padding(top = 6.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
          Text(
            text = "Planes: H:${telemetry.horizontalPlanesCount} | V:${telemetry.verticalPlanesCount}",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFCBD5E1)
          )
          Text(
            text = "Features: ${telemetry.pointCloudPointsCount} pts",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFCBD5E1)
          )
          Text(
            text = "Light: ${telemetry.lightIntensityLumens.toInt()} lm",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFCBD5E1)
          )
          if (telemetry.isDriftActive) {
            Text(
              text = "Drift: Δ=${"%.3f".format(telemetry.accumulatedDriftMeters)}m (${telemetry.driftCategory})",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFFEF4444),
              fontWeight = FontWeight.Bold
            )
          } else {
            Text(
              text = "Drift: Stable (0.00m)",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFF4ADE80)
            )
          }
        }
      }
    }
  }
}
