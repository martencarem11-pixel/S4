package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  sheetState: SheetState,
  ambientIntensity: Float,
  onAmbientChange: (Float) -> Unit,
  sunIntensity: Float,
  onSunChange: (Float) -> Unit,
  showGridFloor: Boolean,
  onGridFloorChange: (Boolean) -> Unit,
  autoRotate: Boolean,
  onAutoRotateChange: (Boolean) -> Unit,
  ipdMm: Float,
  onIpdChange: (Float) -> Unit,
  showDiagnostics: Boolean,
  onDiagnosticsChange: (Boolean) -> Unit,
  modelRollDegrees: Float = 0f,
  onRollChange: (Float) -> Unit = {},
  onResetScene: () -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color(0xFF0F172A),
    contentColor = Color.White,
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(vertical = 12.dp)
          .width(48.dp)
          .height(4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(Color(0xFF475569))
      )
    }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState())
        .testTag("spatial_settings_sheet")
    ) {
      Text(
        text = "Spatial Scene Settings",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
      )
      Text(
        text = "Adjust lighting, stereo rendering, and spatial environment",
        fontSize = 13.sp,
        color = Color(0xFF94A3B8)
      )

      Spacer(modifier = Modifier.height(18.dp))

      // Section: Lighting & Environment
      Text(
        text = "LIGHTING & SHADERS",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF38BDF8),
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Ambient Light: ${(ambientIntensity * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
      }
      Slider(
        value = ambientIntensity,
        onValueChange = onAmbientChange,
        valueRange = 0.2f..2.5f,
        colors = SliderDefaults.colors(
          thumbColor = Color(0xFF38BDF8),
          activeTrackColor = Color(0xFF38BDF8)
        )
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Sun Directional Light: ${(sunIntensity * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
      }
      Slider(
        value = sunIntensity,
        onValueChange = onSunChange,
        valueRange = 0.0f..2.0f,
        colors = SliderDefaults.colors(
          thumbColor = Color(0xFF38BDF8),
          activeTrackColor = Color(0xFF38BDF8)
        )
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Section: MR Stereo Settings
      Text(
        text = "MR STEREO VISUALS",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF38BDF8),
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Interpupillary Distance (IPD): ${ipdMm.toInt()} mm", color = Color.White, fontSize = 14.sp)
      }
      Slider(
        value = ipdMm,
        onValueChange = onIpdChange,
        valueRange = 52.0f..74.0f,
        colors = SliderDefaults.colors(
          thumbColor = Color(0xFF38BDF8),
          activeTrackColor = Color(0xFF38BDF8)
        )
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Section: 3-Axis Orientation (Roll / Z-Axis)
      Text(
        text = "3-AXIS ORIENTATION (ROLL / Z-AXIS)",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF38BDF8),
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Roll (Z-Axis): ${modelRollDegrees.toInt()}°", color = Color.White, fontSize = 14.sp)
      }
      Slider(
        value = modelRollDegrees,
        onValueChange = onRollChange,
        valueRange = -180.0f..180.0f,
        colors = SliderDefaults.colors(
          thumbColor = Color(0xFF38BDF8),
          activeTrackColor = Color(0xFF38BDF8)
        )
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Section: Toggles
      Text(
        text = "DISPLAY & TELEMETRY",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF38BDF8),
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Spatial Grid Floor", color = Color.White, fontSize = 14.sp)
        Switch(
          checked = showGridFloor,
          onCheckedChange = onGridFloorChange,
          colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Auto Orbit Rotation", color = Color.White, fontSize = 14.sp)
        Switch(
          checked = autoRotate,
          onCheckedChange = onAutoRotateChange,
          colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Show Diagnostics & FPS HUD", color = Color.White, fontSize = 14.sp)
        Switch(
          checked = showDiagnostics,
          onCheckedChange = onDiagnosticsChange,
          colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
        )
      }

      Spacer(modifier = Modifier.height(18.dp))

      Button(
        onClick = {
          onResetScene()
          onDismiss()
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("reset_scene_button")
      ) {
        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Reset Camera & Transforms", fontWeight = FontWeight.Bold)
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}
