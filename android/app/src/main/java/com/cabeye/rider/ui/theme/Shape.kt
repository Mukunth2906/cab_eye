package com.cabeye.rider.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens for Cab Eye dark accessibility theme.
 * Uses soft, rounded corners (20-28dp) for cards and buttons, and circles for voice orbs.
 */
object CabEyeShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(18.dp)
    val large = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(24.dp)
    val button = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(50)
    val circle = CircleShape

    val materialShapes = Shapes(
        small = small,
        medium = medium,
        large = large
    )
}
