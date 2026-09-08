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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Explicit Tracking Recovery Affordance.
 * Displayed when ARCore 6DoF tracking is lost or paused.
 * Strictly complies with Tracking-Loss Policy:
 * 1. Confirms anchored content remains frozen at last valid spatial pose.
 * 2. Guides user to recover tracking via gentle scanning.
 * 3. Provides explicit actions to relocalize or recenter without floating camera fallback.
 */
@Composable
fun TrackingRecoveryCard(
  isVisible: Boolean,
  trackingStatus: String,
  onRecenterClick: () -> Unit,
  onDismiss: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
    exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
    modifier = modifier
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(Color(0xEE1E293B))
        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
        .padding(16.dp)
        .testTag("tracking_recovery_card")
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(32.dp)
              .clip(CircleShape)
              .background(Color(0xFFF59E0B).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.WarningAmber,
              contentDescription = "Tracking Recovery",
              tint = Color(0xFFFBBF24),
              modifier = Modifier.size(20.dp)
            )
          }

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Spatial Tracking Paused",
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              color = Color.White
            )
            Text(
              text = "Anchored exhibits held at last valid spatial pose",
              fontSize = 12.sp,
              color = Color(0xFF94A3B8)
            )
          }

          if (onDismiss != null) {
            IconButton(
              onClick = onDismiss,
              modifier = Modifier
                .size(28.dp)
                .testTag("dismiss_tracking_card_button")
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
              )
            }
          }
        }

        Text(
          text = "Slowly point your camera at previously mapped surfaces with good ambient lighting to restore 6DoF tracking.",
          fontSize = 12.sp,
          lineHeight = 16.sp,
          color = Color(0xFFE2E8F0)
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Status: $trackingStatus",
            fontSize = 11.sp,
            color = Color(0xFFF59E0B),
            modifier = Modifier.padding(end = 12.dp)
          )

          OutlinedButton(
            onClick = onRecenterClick,
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = Color(0xFF38BDF8)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("recenter_tracking_button")
          ) {
            Text("Recenter Origin", fontSize = 12.sp)
          }
        }
      }
    }
  }
}
