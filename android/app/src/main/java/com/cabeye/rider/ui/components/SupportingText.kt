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
 * Supporting text component for secondary instructions, spoken hints,
 * or partial transcripts.
 */
@Composable
fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CabEyeColors.TextSecondary
) {
    Text(
        text = text,
        style = CabEyeType.supportingText,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}
