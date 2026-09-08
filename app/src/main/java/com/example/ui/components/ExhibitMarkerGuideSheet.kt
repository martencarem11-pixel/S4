package com.example.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arcore.ExhibitMarker
import com.example.arcore.ImageMarkerCatalog
import com.example.model.SpatialModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExhibitMarkerGuideSheet(
  sheetState: SheetState,
  onSelectModel: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val markers = remember { ImageMarkerCatalog.exhibits }
  var selectedMarker by remember { mutableStateOf(markers.first()) }
  val markerBitmap = remember(selectedMarker) {
    ImageMarkerCatalog.generateMarkerBitmap(selectedMarker).asImageBitmap()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color(0xFF0F172A),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(vertical = 12.dp)
          .size(width = 40.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(Color(0xFF475569))
      )
    },
    modifier = Modifier.testTag("marker_guide_sheet")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 36.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Sheet Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.QrCodeScanner,
              contentDescription = null,
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Image Targets & Exhibits",
              fontWeight = FontWeight.Bold,
              fontSize = 18.sp,
              color = Color.White
            )
          }
          Text(
            text = "Physical Image Recognition & 6DoF Anchoring",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
          )
        }
      }

      // Marker Selector Horizontal Chips
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        items(markers) { marker ->
          val isSelected = marker.markerId == selectedMarker.markerId
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B))
              .border(
                width = 1.dp,
                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155),
                shape = RoundedCornerShape(12.dp)
              )
              .padding(horizontal = 14.dp, vertical = 8.dp)
              .testTag("marker_chip_${marker.markerId}")
          ) {
            Text(
              text = marker.title,
              color = if (isSelected) Color(0xFF0F172A) else Color.White,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              fontSize = 13.sp
            )
          }
        }
      }

      // Active Marker Scannable Target Visual Card
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF020617))
          .border(2.dp, Color(selectedMarker.accentColorHex), RoundedCornerShape(16.dp))
          .padding(16.dp)
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Live Marker Bitmap rendered with crisp AR target patterns
          Image(
            bitmap = markerBitmap,
            contentDescription = "Target Marker for ${selectedMarker.title}",
            modifier = Modifier
              .size(240.dp)
              .clip(RoundedCornerShape(12.dp))
              .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E293B))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = "Target ID: ${selectedMarker.markerId}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF38BDF8)
              )
            }

            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E293B))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = "Width: 15cm (1:1 Metric)",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF4ADE80)
              )
            }
          }
        }
      }

      // Exhibit Details Card
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(Color(0xFF1E293B))
          .padding(14.dp)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = selectedMarker.title,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color.White
          )
          Text(
            text = selectedMarker.category,
            fontSize = 12.sp,
            color = Color(selectedMarker.accentColorHex),
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = selectedMarker.description,
            fontSize = 13.sp,
            color = Color(0xFFCBD5E1),
            lineHeight = 18.sp
          )
        }
      }

      // Action Button: View / Load Model
      Button(
        onClick = {
          onSelectModel(selectedMarker.modelId)
          onDismiss()
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF38BDF8),
          contentColor = Color(0xFF0F172A)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("load_exhibit_button")
      ) {
        Icon(
          imageVector = Icons.Default.ViewInAr,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Inspect Exhibit in 3D / AR",
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp
        )
      }
    }
  }
}
