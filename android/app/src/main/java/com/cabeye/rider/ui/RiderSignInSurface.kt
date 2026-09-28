package com.cabeye.rider.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.auth.SignInStep
import com.cabeye.rider.auth.SignInUi
import com.cabeye.rider.auth.SpokenNumbers
import com.cabeye.rider.auth.TypedField
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.MinTouchTarget

/**
 * The rider's sign-in screen.
 *
 * Built like the ride surface: the top of the screen is one enormous hold-to-talk target whose
 * text is exactly what was just spoken, announced to TalkBack through a live region. Nothing
 * needs to be found or aimed at.
 *
 * Below it, deliberately *outside* the merged press target so TalkBack can reach each control,
 * is a small panel for a sighted helper or a keyboard user: type the number, code or name, or
 * continue without signing in. Typed answers go through exactly the same logic as spoken ones.
 */
@Composable
fun RiderSignInSurface(
    ui: SignInUi,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onTyped: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)

    val accent = when {
        ui.micOpen -> palette.listening
        ui.step == SignInStep.DONE -> palette.confirm
        ui.step == SignInStep.CONFIRM_PHONE || ui.step == SignInStep.CONFIRM_NAME -> palette.clarify
        else -> palette.onBackground
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // -------------------------------------------------------------------------
        //  The press target. Keyed on Unit for the same reason as RiderSurface: a key
        //  that changes mid-press restarts the detector and cuts the rider off.
        // -------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            currentHoldStart()
                            tryAwaitRelease()
                            currentHoldEnd()
                        }
                    )
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = ui.prompt.ifBlank { "Sign in. Hold anywhere and speak." }
                    // No live region: the narrator speaks each prompt, and TalkBack repeating
                    // it on top made both hard to hear.
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (ui.micOpen) "◉ LISTENING" else "SIGN IN",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ui.micOpen) palette.listening else palette.muted
                )
                Spacer(Modifier.height(20.dp))

                if (ui.heard.isNotBlank()) {
                    Text(
                        text = if (ui.heard.all { it.isDigit() }) SpokenNumbers.speakable(ui.heard).trimEnd('.')
                            .replace(". ", "   ") else ui.heard,
                        style = MaterialTheme.typography.displayMedium,
                        color = accent,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                }

                Text(
                    text = ui.prompt,
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.onBackground,
                    textAlign = TextAlign.Center
                )

                if (ui.partial.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "“${ui.partial}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Hold anywhere and speak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center
                )
            }
        }

        // -------------------------------------------------------------------------
        //  Helper panel
        // -------------------------------------------------------------------------
        if (ui.step != SignInStep.DONE) {
            HelperPanel(ui.typedFallback, onTyped, onSkip)
        }
    }
}

@Composable
private fun HelperPanel(field: TypedField?, onTyped: (String) -> Unit, onSkip: () -> Unit) {
    val palette = LocalPalette.current
    var draft by remember(field) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        if (field != null) {
            val (label, keyboard) = when (field) {
                TypedField.PHONE -> "Mobile number" to KeyboardType.Phone
                TypedField.CODE -> "Six digit code" to KeyboardType.NumberPassword
                TypedField.NAME -> "Your name" to KeyboardType.Text
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(40) },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.onBackground,
                        unfocusedTextColor = palette.onBackground,
                        focusedBorderColor = palette.clarify,
                        unfocusedBorderColor = palette.outline,
                        focusedLabelColor = palette.clarify,
                        unfocusedLabelColor = palette.muted
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                PanelButton("OK", palette.confirm) {
                    val value = draft.trim()
                    if (value.isNotEmpty()) {
                        onTyped(value)
                        draft = ""
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        PanelButton("Continue without signing in", palette.muted, fill = true, onClick = onSkip)
    }
}

@Composable
private fun PanelButton(label: String, accent: Color, fill: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = accent
        ),
        modifier = (if (fill) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, accent, RoundedCornerShape(18.dp))
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
