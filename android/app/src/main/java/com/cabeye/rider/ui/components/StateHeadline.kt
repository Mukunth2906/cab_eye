package com.cabeye.rider.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * State headline component for Cab Eye.
 * Renders the primary state or instruction in massive, high-contrast, bold typography.
 */
@Composable
fun StateHeadline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CabEyeColors.TextPrimary,
    isLarge: Boolean = true
) {
    Text(
        text = text,
        style = if (isLarge) CabEyeType.stateTitleLarge else CabEyeType.stateTitle,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
