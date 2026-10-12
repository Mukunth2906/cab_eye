package com.cabeye.rider.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.ui.theme.CabEyeColors
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.runtime.rememberUpdatedState
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * High-contrast, protected SOS trigger.
 *
 * Designed according to Section 20 of specification:
 * - Red accent (#EF4444)
 * - Large touch target (>= 64dp)
 * - Protected against accidental taps via 2-second press-and-hold (or deliberate long press)
 * - Visual feedback while holding
 */
@Composable
fun SosButton(
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "SOS"
) {
    var isHolding by remember { mutableStateOf(false) }

    val holdProgress by animateFloatAsState(
        targetValue = if (isHolding) 1f else 0f,
        animationSpec = tween(durationMillis = 2000),
        label = "sosHoldProgress"
    )

    val palette = LocalPalette.current
    val currentOnSos by rememberUpdatedState(onSos)

    // The one trigger: the full two-second hold. (A system long press used to fire it as well,
    // after about half a second, so it went off early and then a second time.)
    if (holdProgress >= 0.99f && isHolding) {
        isHolding = false
        onSos()
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = CabEyeSpacing.prominentTouchTarget, minHeight = CabEyeSpacing.prominentTouchTarget)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isHolding) palette.danger.copy(alpha = 0.10f + 0.25f * holdProgress) else palette.surface)
            .border(3.dp, palette.danger, RoundedCornerShape(20.dp))
            .semantics {
                role = Role.Button
                contentDescription = "Emergency S O S. Press and hold for two seconds to activate."
                // TalkBack: double-tap and hold works as a hold; the actions menu offers it too.
                onLongClick(label = "Activate S O S") { currentOnSos(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(palette.danger, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isHolding) "HOLD ${(2 - 2 * holdProgress).coerceAtLeast(0f).let { kotlin.math.ceil(it).toInt() }}s" else label,
                style = CabEyeType.buttonText.copy(fontSize = 18.sp, fontWeight = FontWeight.Black),
                color = palette.danger
            )
        }
    }
}

/**
 * Full-screen SOS emergency overlay.
 *
 * Keeps underlying ride state safe in memory while presenting high-visibility
 * emergency actions and location sharing.
 */
@Composable
fun EmergencyOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** What happened to the SOS: sending, sent, or not delivered. Shown as it is. */
    status: String = ""
) {
    val palette = LocalPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(CabEyeSpacing.screenPadding),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(palette.danger.copy(alpha = 0.15f), CircleShape)
                    .border(6.dp, palette.danger, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "☎",
                    fontSize = 48.sp,
                    color = palette.danger
                )
            }

            Spacer(Modifier.size(CabEyeSpacing.lg))

            Text(
                text = "EMERGENCY ASSISTANCE",
                style = CabEyeType.badgeText,
                color = palette.danger
            )

            Spacer(Modifier.size(CabEyeSpacing.sm))

            Text(
                text = "SOS ACTIVE",
                style = CabEyeType.stateTitleLarge,
                color = palette.danger
            )

            Spacer(Modifier.size(CabEyeSpacing.md))

            Text(
                text = status.ifBlank { "Sending your S O S to Cab Eye support…" },
                style = CabEyeType.supportingText,
                color = palette.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive }
            )

            Spacer(Modifier.size(CabEyeSpacing.xl))

            SecondaryAction(
                label = "DISMISS EMERGENCY",
                onClick = onDismiss,
                accentColor = palette.danger,
                containerColor = palette.surface
            )
        }
    }
}
