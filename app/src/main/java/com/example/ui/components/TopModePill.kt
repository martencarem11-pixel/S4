package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DisplayMode

@Composable
fun TopModePill(
  currentMode: DisplayMode,
  onModeSelected: (DisplayMode) -> Unit,
  modifier: Modifier = Modifier
) {
  val modes = listOf(DisplayMode.MR, DisplayMode.AR, DisplayMode.OBJECT)

  // Outer pill background matching screenshot: soft rounded container
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(36.dp))
      .background(Color(0xFF9EABB7))
      .padding(horizontal = 6.dp, vertical = 6.dp)
      .testTag("top_mode_pill_container")
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      modes.forEach { mode ->
        val isSelected = currentMode == mode
        val title = when (mode) {
          DisplayMode.MR -> "MR"
          DisplayMode.AR -> "AR"
          DisplayMode.OBJECT -> "Object"
        }

        val pillBgColor by animateColorAsState(
          targetValue = if (isSelected) Color(0xFF758595) else Color.Transparent,
          animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
          label = "pill_bg_anim"
        )
        val textColor by animateColorAsState(
          targetValue = if (isSelected) Color(0xFF1E293B) else Color(0xFF475569),
          label = "pill_text_color"
        )

        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .clip(RoundedCornerShape(26.dp))
            .background(pillBgColor)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
            ) {
              onModeSelected(mode)
            }
            .padding(horizontal = 24.dp, vertical = 11.dp)
            .testTag("mode_tab_${mode.name.lowercase()}")
        ) {
          Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            color = textColor
          )
        }
      }
    }
  }
}

