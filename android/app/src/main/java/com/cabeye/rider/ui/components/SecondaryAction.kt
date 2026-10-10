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
 * Secondary action button for cancellations, skips, or secondary options.
 */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = CabEyeColors.TextSecondary,
    containerColor: Color = CabEyeColors.Background,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CabEyeShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = accentColor,
            disabledContainerColor = CabEyeColors.Background,
            disabledContentColor = CabEyeColors.TextMuted
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CabEyeSpacing.preferredTouchTarget)
            .border(2.dp, accentColor.copy(alpha = 0.7f), CabEyeShapes.button)
    ) {
        Text(
            text = label,
            style = CabEyeType.buttonText,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }
}
