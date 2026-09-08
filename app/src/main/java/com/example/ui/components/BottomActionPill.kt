package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BottomActionPill(
  isRecording: Boolean,
  recordingDurationSec: Int,
  onPhotoClick: () -> Unit,
  onRecClick: () -> Unit,
  onOpenClick: () -> Unit,
  onClearClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1.0f,
    targetValue = if (isRecording) 1.15f else 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(600),
      repeatMode = RepeatMode.Reverse
    ),
    label = "rec_pulse_scale"
  )

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier.fillMaxWidth()
  ) {
    // Live Recording Duration Badge
    if (isRecording) {
      val minutes = recordingDurationSec / 60
      val seconds = recordingDurationSec % 60
      val timeString = String.format("%02d:%02d", minutes, seconds)

      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFFDC2626).copy(alpha = 0.85f))
          .padding(horizontal = 14.dp, vertical = 4.dp)
          .testTag("recording_duration_badge")
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(Color.White)
          )
          Text(
            text = "REC $timeString",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
    }

    // Outer Pill Container matching screenshot style
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(40.dp))
        .background(Color(0xFFB3BCC7))
        .padding(horizontal = 14.dp, vertical = 6.dp)
        .testTag("bottom_action_pill_container")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.width(320.dp)
      ) {
        // PHOTO Button
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true, color = Color.White)
            ) { onPhotoClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("action_photo_button")
        ) {
          Text(
            text = "PHOTO",
            color = Color(0xFF202A37),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 0.5.sp
          )
        }

        // REC Red Circle Button
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .scale(pulseScale)
            .size(54.dp)
            .clip(CircleShape)
            .background(if (isRecording) Color(0xFFB91C1C) else Color(0xFFEE3A3A))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true, color = Color.White)
            ) { onRecClick() }
            .testTag("action_rec_button")
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            if (isRecording) {
              Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop Recording",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
            } else {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(Color.White)
              )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = if (isRecording) "STOP" else "REC",
              color = Color.White,
              fontWeight = FontWeight.Black,
              fontSize = 9.sp,
              letterSpacing = 0.5.sp
            )
          }
        }

        // Open Button
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true, color = Color.White)
            ) { onOpenClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("action_open_button")
        ) {
          Text(
            text = "Open",
            color = Color(0xFF202A37),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }

        // Clear Button
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = ripple(bounded = true, color = Color.White)
            ) { onClearClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("action_clear_button")
        ) {
          Text(
            text = "Clear",
            color = Color(0xFF202A37),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }
      }
    }
  }
}
