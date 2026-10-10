package com.cabeye.rider.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * Primary high-contrast action button.
 *
 * Meets all accessibility touch target guidelines (min 56dp height),
 * rounded corners, bold 20sp typography, and distinct focus border.
 */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = CabEyeColors.SuccessGreen,
    containerColor: Color = CabEyeColors.SurfaceElevated,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CabEyeShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = accentColor,
            disabledContainerColor = CabEyeColors.SurfaceDark,
            disabledContentColor = CabEyeColors.TextMuted
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CabEyeSpacing.prominentTouchTarget)
            .border(3.dp, accentColor, CabEyeShapes.button)
    ) {
        Text(
            text = label,
            style = CabEyeType.buttonText,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}
