package com.cabeye.rider.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * Status indicator chip that pairs a glyph/icon with a text label,
 * strictly upholding the rule that color alone must never convey state.
 */
@Composable
fun StatusIndicator(
    label: String,
    icon: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = 0.12f), CabEyeShapes.pill)
            .border(1.5.dp, accent.copy(alpha = 0.5f), CabEyeShapes.pill)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$icon  $label".uppercase(),
                style = CabEyeType.badgeText.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                color = accent
            )
        }
    }
}
