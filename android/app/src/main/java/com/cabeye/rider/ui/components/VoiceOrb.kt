package com.cabeye.rider.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeType
import com.cabeye.rider.ui.theme.LocalReducedMotion

/**
 * State of the central voice orb.
 */
enum class VoiceOrbState {
    READY,
    LISTENING,
    PROCESSING,
    SUCCESS,
    ERROR
}

/**
 * Central voice orb visual interaction component.
 *
 * Implements a glowing, pulsing circular voice indicator with high contrast,
 * rich concentric rings, microphone/status icons, and reduced-motion compliance.
 *
 * @param state current orb state (READY, LISTENING, PROCESSING, SUCCESS, ERROR)
 * @param inputLevel normalized 0..1 audio level from microphone
 * @param modifier modifier for layout
 * @param customGlyph optional override glyph (e.g. "🚕" for driver/finding states)
 * @param customLabel optional override label (e.g. "FINDING")
 * @param customAccent optional override color (e.g. WarningYellow)
 * @param orbSize diameter of the core orb
 */
@Composable
fun VoiceOrb(
    state: VoiceOrbState,
    modifier: Modifier = Modifier,
    inputLevel: Float = 0f,
    customGlyph: String? = null,
    customLabel: String? = null,
    customAccent: Color? = null,
    orbSize: Dp = 150.dp
) {
    val reducedMotion = LocalReducedMotion.current

    // Base colors according to state
    val accent = customAccent ?: when (state) {
        VoiceOrbState.READY -> CabEyeColors.VoiceBlue
        VoiceOrbState.LISTENING -> CabEyeColors.VoiceBlue
        VoiceOrbState.PROCESSING -> CabEyeColors.ProcessingPurple
        VoiceOrbState.SUCCESS -> CabEyeColors.SuccessGreen
        VoiceOrbState.ERROR -> CabEyeColors.DangerRed
    }

    val subtleGlow = when (state) {
        VoiceOrbState.READY -> CabEyeColors.VoiceBlueSubtle
        VoiceOrbState.LISTENING -> CabEyeColors.VoiceBlueSubtle
        VoiceOrbState.PROCESSING -> CabEyeColors.ProcessingPurpleSubtle
        VoiceOrbState.SUCCESS -> CabEyeColors.SuccessGreenSubtle
        VoiceOrbState.ERROR -> CabEyeColors.DangerRedSubtle
    }

    val glyph = customGlyph ?: when (state) {
        VoiceOrbState.READY -> "🎙"
        VoiceOrbState.LISTENING -> "🎙"
        VoiceOrbState.PROCESSING -> "◉"
        VoiceOrbState.SUCCESS -> "✓"
        VoiceOrbState.ERROR -> "!"
    }

    val labelText = customLabel ?: when (state) {
        VoiceOrbState.READY -> "READY"
        VoiceOrbState.LISTENING -> "LISTENING"
        VoiceOrbState.PROCESSING -> "PROCESSING"
        VoiceOrbState.SUCCESS -> "SUCCESS"
        VoiceOrbState.ERROR -> "ALERT"
    }

    // Animations
    val transition = rememberInfiniteTransition(label = "voiceOrbAnim")

    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val outerRingScale by transition.animateFloat(
        initialValue = 1.15f,
        targetValue = 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRingScale"
    )

    val outerRingAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRingAlpha"
    )

    val processingRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Dynamic scale driven by audio input
    val audioNudge = 1f + (inputLevel.coerceIn(0f, 1f) * 0.18f)
    val effectiveScale = when {
        reducedMotion -> 1f
        state == VoiceOrbState.LISTENING -> pulseScale * audioNudge
        state == VoiceOrbState.PROCESSING -> 1.03f
        else -> 1f
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(orbSize * 1.45f),
            contentAlignment = Alignment.Center
        ) {
            // Outermost radiating ring during active listening
            if (state == VoiceOrbState.LISTENING && !reducedMotion) {
                Box(
                    modifier = Modifier
                        .size(orbSize)
                        .scale(outerRingScale * audioNudge)
                        .alpha(outerRingAlpha)
                        .border(3.dp, accent, CircleShape)
                )
            }

            // Secondary glow backdrop
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .scale(if (!reducedMotion && (state == VoiceOrbState.LISTENING || state == VoiceOrbState.PROCESSING)) 1.16f else 1.08f)
                    .background(subtleGlow, CircleShape)
            )

            // Outer animated / decorative border ring
            val rotatingModifier = if (state == VoiceOrbState.PROCESSING && !reducedMotion) {
                Modifier.rotate(processingRotation)
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .size(orbSize)
                    .scale(effectiveScale)
                    .then(rotatingModifier)
                    .border(
                        width = when (state) {
                            VoiceOrbState.LISTENING -> 8.dp
                            VoiceOrbState.PROCESSING -> 6.dp
                            VoiceOrbState.SUCCESS -> 7.dp
                            VoiceOrbState.ERROR -> 7.dp
                            VoiceOrbState.READY -> 4.dp
                        },
                        brush = if (state == VoiceOrbState.PROCESSING) {
                            Brush.sweepGradient(
                                listOf(
                                    accent.copy(alpha = 0.2f),
                                    accent,
                                    accent.copy(alpha = 0.3f),
                                    accent
                                )
                            )
                        } else {
                            Brush.radialGradient(listOf(accent, accent))
                        },
                        shape = CircleShape
                    )
                    .background(CabEyeColors.SurfaceDark, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Main icon/glyph inside the orb
                Text(
                    text = glyph,
                    fontSize = if (glyph.length > 2) 36.sp else 46.sp,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // State Badge text
        Box(
            modifier = Modifier
                .background(subtleGlow, CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape)
        ) {
            Text(
                text = labelText,
                style = CabEyeType.badgeText,
                color = accent,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
