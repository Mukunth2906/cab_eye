package com.cabeye.rider.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cab Eye Design System Colors.
 *
 * Designed for high-contrast, premium dark mode accessibility:
 * - Background: Almost-black (#050505) avoiding halation/glare.
 * - Secondary surfaces: #151515 and #202020 for minimal cards and overlays.
 * - Primary text: Pure White (#FFFFFF) for maximum legibility.
 * - Secondary text: #BDBDBD for subtle supporting labels.
 * - Semantic colors for voice, processing, warnings, confirmations, and SOS.
 */
object CabEyeColors {
    // Surface & Background
    val Background = Color(0xFF050505)
    val SurfaceDark = Color(0xFF151515)
    val SurfaceElevated = Color(0xFF202020)
    val CardBorder = Color(0xFF2D2D35)
    val CardBorderHighlight = Color(0xFF454555)

    // Typography
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFBDBDBD)
    val TextMuted = Color(0xFF8E8E93)

    // Semantic Accents
    val VoiceBlue = Color(0xFF38BDF8)         // Voice interaction / active listening
    val VoiceBlueSubtle = Color(0x2638BDF8)   // Pulsing glow rings
    val ProcessingPurple = Color(0xFFA855F7)   // Processing / AI understanding
    val ProcessingPurpleSubtle = Color(0x26A855F7)
    val WarningYellow = Color(0xFFFBBF24)     // Finding / driver approaching
    val WarningYellowSubtle = Color(0x26FBBF24)
    val SuccessGreen = Color(0xFF22C55E)      // Success / verified / completed
    val SuccessGreenSubtle = Color(0x2622C55E)
    val DangerRed = Color(0xFFEF4444)         // Emergency / destructive action
    val DangerRedSubtle = Color(0x26EF4444)
}
