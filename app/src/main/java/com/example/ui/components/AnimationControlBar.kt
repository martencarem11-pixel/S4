package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

@Composable
fun AnimationControlBar(
  isPlaying: Boolean,
  currentTimeSec: Float,
  durationSec: Float,
  speed: Float,
  currentTrack: Int = 0,
  totalTracks: Int = 1,
  onPlayPauseToggle: () -> Unit,
  onSpeedChange: (Float) -> Unit,
  onScrubTime: (Float) -> Unit,
  onTrackChange: (Int) -> Unit = {},
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(24.dp))
      .background(Color(0xFF0F172A).copy(alpha = 0.88f))
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .testTag("animation_control_bar")
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        // Play / Pause Button
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF0284C7))
            .clickable { onPlayPauseToggle() }
            .testTag("anim_play_pause_button")
        ) {
          Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
          )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Time scrubber slider
        Slider(
          value = currentTimeSec.coerceIn(0f, durationSec),
          onValueChange = onScrubTime,
          valueRange = 0f..durationSec,
          colors = SliderDefaults.colors(
            thumbColor = Color(0xFF38BDF8),
            activeTrackColor = Color(0xFF38BDF8),
            inactiveTrackColor = Color(0xFF334155)
          ),
          modifier = Modifier
            .weight(1f)
            .testTag("anim_scrub_slider")
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Timestamp
        Text(
          text = "${String.format("%.1f", currentTimeSec)}s / ${String.format("%.1f", durationSec)}s",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = Color(0xFF94A3B8)
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Speed Chip
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .clickable {
              val nextSpeed = when (speed) {
                0.5f -> 1.0f
                1.0f -> 1.5f
                1.5f -> 2.0f
                else -> 0.5f
              }
              onSpeedChange(nextSpeed)
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("anim_speed_chip")
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.FastForward,
              contentDescription = "Speed",
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "${speed}x",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp
            )
          }
        }
      }
    }
  }
}
