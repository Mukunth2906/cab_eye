package com.cabeye.rider.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * Clean, minimal header for the Cab Eye voice surface.
 *
 * Displays:
 * - App title "Cab Eye"
 * - Optional city indicator badge
 * - Hidden 5-tap connection/settings chip in corner
 */
@Composable
fun CabEyeHeader(
    modifier: Modifier = Modifier,
    cityName: String? = null,
    connected: Boolean = true,
    onOpenSettings: () -> Unit = {}
) {
    var taps by remember { mutableIntStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CabEyeSpacing.screenPadding, vertical = CabEyeSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Cab Eye",
                style = CabEyeType.buttonText.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                color = CabEyeColors.TextPrimary
            )
            if (!cityName.isNullOrBlank()) {
                Text(
                    text = cityName.uppercase(),
                    style = CabEyeType.secondaryInfo.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    ),
                    color = CabEyeColors.TextMuted
                )
            }
        }

        // Connection status & settings entrance
        // Hidden 5-tap mechanism preserved for accessibility safety
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = CabEyeSpacing.minTouchTarget, minHeight = CabEyeSpacing.minTouchTarget)
                .clearAndSetSemantics { }
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    val now = System.currentTimeMillis()
                    if (now - firstTapAt > 3_000L) {
                        firstTapAt = now
                        taps = 1
                    } else {
                        taps++
                    }
                    if (taps >= 5) {
                        taps = 0
                        onOpenSettings()
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        if (connected) CabEyeColors.SurfaceDark else CabEyeColors.DangerRed.copy(alpha = 0.2f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (connected) CabEyeColors.SuccessGreen else CabEyeColors.DangerRed,
                            CircleShape
                        )
                )
                if (!connected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "offline",
                        style = CabEyeType.secondaryInfo.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = CabEyeColors.DangerRed
                    )
                }
            }
        }
    }
}
