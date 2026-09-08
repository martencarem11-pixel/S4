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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arcore.ExhibitSource
import com.example.model.SpatialAnchor

@Composable
fun NearbyExhibitOverlay(
  nearbyExhibit: SpatialAnchor?,
  activeAnchorsCount: Int,
  walkingMeters: Float,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    // 1. Walking Distance & Active Anchors Tracking Chip
    if (activeAnchorsCount > 0 || walkingMeters > 0.1f) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(20.dp))
          .background(Color(0xFF0F172A).copy(alpha = 0.85f))
          .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
          .padding(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.DirectionsWalk,
              contentDescription = null,
              tint = Color(0xFFA78BFA),
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "${String.format("%.1f", walkingMeters)}m walked",
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = Color.White
            )
          }

          Box(
            modifier = Modifier
              .size(4.dp)
              .clip(CircleShape)
              .background(Color(0xFF64748B))
          )

          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.LocationOn,
              contentDescription = null,
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$activeAnchorsCount exhibits in scene",
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = Color(0xFF38BDF8)
            )
          }
        }
      }
    }

    // 2. Interactive Proximity Card for Nearby Exhibit
    AnimatedVisibility(
      visible = nearbyExhibit != null,
      enter = fadeIn() + slideInVertically { it / 2 },
      exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
      if (nearbyExhibit != null) {
        val isImageMarker = nearbyExhibit.source == ExhibitSource.IMAGE_MARKER
        val accentColor = if (isImageMarker) Color(0xFF38BDF8) else Color(0xFF22C55E)

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.92f))
            .border(1.5.dp, accentColor.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("nearby_exhibit_card")
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = if (isImageMarker) Icons.Default.QrCodeScanner else Icons.Default.LocationOn,
                  contentDescription = null,
                  tint = accentColor,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = if (isImageMarker) "IMAGE MARKER ANCHORED" else "PLANE ANCHORED",
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = accentColor
                )
              }

              Text(
                text = nearbyExhibit.modelTitle.ifEmpty { "3D Spatial Exhibit" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }

            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.2f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
              Text(
                text = "${String.format("%.2f", nearbyExhibit.distanceToCameraMeters)}m",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White
              )
            }
          }
        }
      }
    }
  }
}
