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
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * - Connection status, and a Settings button (one tap)
 */
@Composable
fun CabEyeHeader(
    modifier: Modifier = Modifier,
    cityName: String? = null,
    connected: Boolean = true,
    onOpenSettings: () -> Unit = {},
    /** Opens the mind map screen; null hides the button. */
    onOpenFeatures: (() -> Unit)? = null,
    /** Opens the explore module (camera, no ride); null hides the button. */
    onOpenExplore: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Connection status: green dot when the server is reachable, "offline" when not.
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
            Spacer(Modifier.width(10.dp))
            // Settings: a plain button, one tap. Rider or driver mode, server address, account.
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = CabEyeSpacing.minTouchTarget, minHeight = CabEyeSpacing.minTouchTarget)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CabEyeColors.SurfaceElevated)
                    .border(1.5.dp, CabEyeColors.CardBorderHighlight, RoundedCornerShape(14.dp))
                    .clickable(onClickLabel = "Open settings", role = Role.Button) { onOpenSettings() }
                    .semantics { contentDescription = "Settings. Switch to driver mode, server address, account." }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙  Settings",
                    style = CabEyeType.buttonText.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = CabEyeColors.TextPrimary
                )
            }
        }
    }
    if (onOpenFeatures != null || onOpenExplore != null) Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CabEyeSpacing.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
    if (onOpenExplore != null) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = CabEyeSpacing.minTouchTarget)
                .clip(RoundedCornerShape(14.dp))
                .background(CabEyeColors.SurfaceElevated)
                .border(1.5.dp, CabEyeColors.SuccessGreen, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Open explore", role = Role.Button) { onOpenExplore() }
                .semantics { contentDescription = "Explore around me with the camera. No ride needed." }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "📷  Explore",
                style = CabEyeType.buttonText.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = CabEyeColors.SuccessGreen
            )
        }
    }
    if (onOpenFeatures != null) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = CabEyeSpacing.minTouchTarget)
                .clip(RoundedCornerShape(14.dp))
                .background(CabEyeColors.SurfaceElevated)
                .border(1.5.dp, CabEyeColors.VoiceBlue, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Open my mind map", role = Role.Button) { onOpenFeatures() }
                .semantics { contentDescription = "My mind map. Places from your rides, spots and walks from exploring, and a map." }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🧭  My mind map",
                style = CabEyeType.buttonText.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = CabEyeColors.VoiceBlue
            )
        }
    }
    }
    }
}
