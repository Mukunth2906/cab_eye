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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.auth.SignInStep
import com.cabeye.rider.auth.SignInUi
import com.cabeye.rider.auth.SpokenNumbers
import com.cabeye.rider.auth.TypedField
import com.cabeye.rider.ui.components.VoiceOrb
import com.cabeye.rider.ui.components.VoiceOrbState
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.MinTouchTarget

/**
 * The rider's sign-in screen.
 *
 * Built like the ride surface: the top of the screen is one enormous hold-to-talk target whose
 * text is exactly what was just spoken. Nothing needs to be found or aimed at.
 *
 * Every stage is *visible* as well as spoken, for a low-vision rider or a helper standing by:
 * which step of three this is, whether the microphone is open, the words just heard, the digits
 * of the number collected so far, the text message carrying the code, and the code filling in.
 *
 * Below it, deliberately *outside* the merged press target so TalkBack can reach each control,
 * is a panel for a sighted helper or a keyboard user: type the answer, start the number again,
 * send the code again, or continue without signing in. Typed answers go through exactly the
 * same logic as spoken ones.
 */
@Composable
fun RiderSignInSurface(
    ui: SignInUi,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onTyped: (String) -> Unit,
    onSkip: () -> Unit,
    onClearNumber: () -> Unit = {},
    onResend: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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
        // Settings (driver mode, server address): one tap, outside the hold-to-talk area.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            PanelButton("⚙  Settings", palette.onBackground, onClick = onOpenSettings)
        }

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
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StepProgress(ui.step)
                Spacer(Modifier.height(16.dp))

                VoiceOrb(
                    state = when {
                        ui.micOpen -> VoiceOrbState.LISTENING
                        ui.step == SignInStep.SENDING || ui.step == SignInStep.VERIFYING -> VoiceOrbState.PROCESSING
                        ui.step == SignInStep.DONE -> VoiceOrbState.SUCCESS
                        else -> VoiceOrbState.READY
                    },
                    customGlyph = when {
                        ui.micOpen -> null
                        ui.step == SignInStep.DONE -> "✓"
                        ui.step == SignInStep.SENDING || ui.step == SignInStep.VERIFYING -> "…"
                        else -> null
                    },
                    customLabel = when {
                        ui.micOpen -> "LISTENING"
                        ui.step == SignInStep.SENDING -> "SENDING CODE"
                        ui.step == SignInStep.VERIFYING -> "CHECKING"
                        ui.step == SignInStep.DONE -> "SIGNED IN"
                        ui.step == SignInStep.CHECKING -> "STARTING"
                        else -> "HOLD TO TALK"
                    },
                    customAccent = if (ui.micOpen) palette.listening else accent,
                    orbSize = 120.dp
                )
                Spacer(Modifier.height(16.dp))

                when (ui.step) {
                    SignInStep.ASK_PHONE -> NumberSlots(digits = ui.phoneSoFar, accent = palette.listening)
                    SignInStep.CONFIRM_PHONE -> if (ui.heard.isNotBlank()) {
                        BigValue(SpokenNumbers.speakable(ui.heard).trimEnd('.').replace(". ", "   "), accent)
                    }
                    SignInStep.SENDING, SignInStep.ASK_CODE, SignInStep.VERIFYING -> if (ui.phone.isNotBlank()) {
                        CodePanel(ui)
                    }
                    SignInStep.CONFIRM_NAME, SignInStep.ASK_NAME -> if (ui.heard.isNotBlank()) BigValue(ui.heard, accent)
                    else -> Unit
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = ui.prompt,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onBackground,
                    textAlign = TextAlign.Center
                )

                if (ui.partial.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    HeardCard(ui.partial, live = ui.micOpen)
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = when {
                        ui.micOpen -> "Listening while you hold — pauses are fine. Let go when you've finished."
                        ui.step == SignInStep.SENDING || ui.step == SignInStep.VERIFYING -> "One moment…"
                        ui.step == SignInStep.DONE || ui.step == SignInStep.CHECKING -> ""
                        else -> "Hold the screen, or a volume button, and speak. Let go when you've finished."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center
                )
            }
        }

        // -------------------------------------------------------------------------
        //  Helper panel
        // -------------------------------------------------------------------------
        if (ui.step != SignInStep.DONE && ui.step != SignInStep.CHECKING) {
            HelperPanel(ui, onTyped, onSkip, onClearNumber, onResend)
        }
    }
}

/** "1 Number · 2 Code · 3 Name", with the current step lit. */
@Composable
private fun StepProgress(step: SignInStep) {
    val palette = LocalPalette.current
    val current = when (step) {
        SignInStep.ASK_PHONE, SignInStep.CONFIRM_PHONE -> 0
        SignInStep.SENDING, SignInStep.ASK_CODE, SignInStep.VERIFYING -> 1
        SignInStep.ASK_NAME, SignInStep.CONFIRM_NAME -> 2
        SignInStep.DONE -> 3
        else -> -1
    }
    if (current < 0) {
        Text("SIGN IN", style = MaterialTheme.typography.labelLarge, color = palette.muted)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Number", "Code", "Name").forEachIndexed { i, label ->
            val done = i < current
            val active = i == current
            val color = when {
                active -> palette.listening
                done -> palette.confirm
                else -> palette.muted
            }
            Box(
                modifier = Modifier
                    .border(if (active) 2.dp else 1.dp, color, CabEyeShapes.pill)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = (if (done) "✓ " else "${i + 1}  ") + label,
                    style = MaterialTheme.typography.labelLarge,
                    color = color
                )
            }
        }
    }
}

/** Ten boxes, filled with the digits collected so far: "98765 43___". */
@Composable
private fun NumberSlots(digits: String, accent: Color) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 10) {
            if (i == 5) Spacer(Modifier.width(10.dp))
            val d = digits.getOrNull(i)
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 40.dp)
                    .background(palette.surface, RoundedCornerShape(6.dp))
                    .border(
                        if (d != null) 2.dp else 1.dp,
                        if (d != null) accent else palette.outline,
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = d?.toString() ?: "",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = palette.onBackground
                )
            }
        }
    }
}

@Composable
private fun BigValue(text: String, accent: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        color = accent,
        textAlign = TextAlign.Center
    )
}

/** The words just heard, so a rider with some sight — or a helper — can see what was understood. */
@Composable
private fun HeardCard(text: String, live: Boolean) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface, CabEyeShapes.medium)
            .border(1.dp, if (live) palette.listening else palette.outline, CabEyeShapes.medium)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (live) "HEARING" else "I HEARD",
            style = MaterialTheme.typography.labelMedium,
            color = if (live) palette.listening else palette.muted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "“$text”",
            style = MaterialTheme.typography.titleLarge,
            color = palette.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Where the code went, the text message it arrived in, and six boxes filling with it.
 */
@Composable
private fun CodePanel(ui: SignInUi) {
    val palette = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Code sent to +91 ${ui.phone.take(5)} ${ui.phone.drop(5)}",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))

        when {
            ui.smsPreview.isNotBlank() -> MessageBubble(ui.smsPreview)
            ui.step == SignInStep.ASK_CODE && ui.smsTexted -> Text(
                text = "Waiting for the text message… When your phone asks, tap Allow and the code fills in by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.clarify,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 6) {
                val d = ui.code.getOrNull(i)
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 52.dp)
                        .background(palette.surface, RoundedCornerShape(8.dp))
                        .border(
                            if (d != null) 2.dp else 1.dp,
                            if (d != null) palette.confirm else palette.outline,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = d?.toString() ?: "",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onBackground
                    )
                }
            }
        }
    }
}

/** The code's text message, drawn the way a messages app shows an incoming one. */
@Composable
private fun MessageBubble(message: String) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceElevated, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
            .padding(14.dp)
    ) {
        Text("CABEYE · now", style = MaterialTheme.typography.labelMedium, color = palette.muted)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = palette.onBackground)
    }
}

@Composable
private fun HelperPanel(
    ui: SignInUi,
    onTyped: (String) -> Unit,
    onSkip: () -> Unit,
    onClearNumber: () -> Unit,
    onResend: () -> Unit
) {
    val palette = LocalPalette.current
    val field = ui.typedFallback
    var draft by remember(field) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        if (field != null) {
            val (label, keyboard) = when (field) {
                TypedField.PHONE -> "Or type the mobile number" to KeyboardType.Phone
                TypedField.CODE -> "Or type the six digit code" to KeyboardType.NumberPassword
                TypedField.NAME -> "Or type your name" to KeyboardType.Text
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
            Spacer(Modifier.height(10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (ui.step == SignInStep.ASK_PHONE && ui.phoneSoFar.isNotEmpty()) {
                PanelButton("Start number again", palette.clarify, modifier = Modifier.weight(1f), onClick = onClearNumber)
            }
            if (ui.step == SignInStep.ASK_CODE) {
                PanelButton("Send code again", palette.clarify, modifier = Modifier.weight(1f), onClick = onResend)
            }
        }
        if ((ui.step == SignInStep.ASK_PHONE && ui.phoneSoFar.isNotEmpty()) || ui.step == SignInStep.ASK_CODE) {
            Spacer(Modifier.height(10.dp))
        }
        PanelButton("Continue without signing in", palette.muted, modifier = Modifier.fillMaxWidth(), onClick = onSkip)
    }
}

@Composable
private fun PanelButton(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = accent
        ),
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, accent, RoundedCornerShape(18.dp))
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
