package com.cabeye.rider.driver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.MinTouchTarget

/**
 * Driver sign-in and profile: phone → code → profile.
 *
 * Same visual language as [DriverSurface] — outlined, high-contrast, one obvious action per
 * screen — because the same driver uses both, often at a kerb in daylight.
 *
 * @param canCancel true only when editing a profile that is already complete; an incomplete
 *   profile cannot be skipped, since the rider would be told nothing about which car to find
 */
@Composable
fun DriverAccountScreen(
    ui: DriverAccountUi,
    canCancel: Boolean,
    onPhoneChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onChangeNumber: () -> Unit,
    onField: (DriverField, String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("DRIVER", style = MaterialTheme.typography.labelLarge, color = palette.muted)
                SmallButton("Settings", palette.muted, onSettings)
            }
            Spacer(Modifier.height(24.dp))

            when (ui.step) {
                DriverAccountStep.PHONE -> {
                    Title("Sign in")
                    Hint("Enter your mobile number. We'll send a one-time code.")
                    Spacer(Modifier.height(20.dp))
                    Field("Mobile number", ui.phone, KeyboardType.Phone, onPhoneChange)
                    Spacer(Modifier.height(20.dp))
                    WideButton(if (ui.busy) "SENDING…" else "SEND CODE", palette.confirm, enabled = !ui.busy, onClick = onSendCode)
                }

                DriverAccountStep.CODE -> {
                    Title("Enter code")
                    Hint("Sent to ${ui.phone}")
                    Spacer(Modifier.height(20.dp))
                    Field("Six digit code", ui.code, KeyboardType.NumberPassword, onCodeChange)
                    Spacer(Modifier.height(20.dp))
                    WideButton(if (ui.busy) "CHECKING…" else "VERIFY", palette.confirm, enabled = !ui.busy, onClick = onVerify)
                    Spacer(Modifier.height(12.dp))
                    WideButton("RESEND CODE", palette.clarify, enabled = !ui.busy, onClick = onSendCode)
                    Spacer(Modifier.height(12.dp))
                    WideButton("CHANGE NUMBER", palette.muted, enabled = !ui.busy, onClick = onChangeNumber)
                }

                DriverAccountStep.PROFILE -> {
                    Title(if (ui.editing) "Your profile" else "Set up your profile")
                    Hint("A blind rider hears your name, and your vehicle's colour and model, when you accept.")
                    if (ui.stats.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(ui.stats, style = MaterialTheme.typography.bodyMedium, color = palette.confirm)
                    }
                    Spacer(Modifier.height(20.dp))

                    Field("Your name *", ui.name, KeyboardType.Text) { onField(DriverField.NAME, it) }
                    Spacer(Modifier.height(12.dp))

                    Text("Vehicle type", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Choice("AUTO", ui.vehicleType == "AUTO") { onField(DriverField.VEHICLE_TYPE, "AUTO") }
                        Choice("CAB", ui.vehicleType == "CAB") { onField(DriverField.VEHICLE_TYPE, "CAB") }
                    }
                    Spacer(Modifier.height(12.dp))

                    Field("Vehicle model * (e.g. Bajaj RE)", ui.vehicleModel, KeyboardType.Text) {
                        onField(DriverField.VEHICLE_MODEL, it)
                    }
                    Spacer(Modifier.height(12.dp))
                    Field("Colour (e.g. Yellow)", ui.vehicleColour, KeyboardType.Text) {
                        onField(DriverField.VEHICLE_COLOUR, it)
                    }
                    Spacer(Modifier.height(12.dp))
                    Field("Number plate * (e.g. TN 38 AB 1234)", ui.vehiclePlate, KeyboardType.Text) {
                        onField(DriverField.VEHICLE_PLATE, it)
                    }
                    Spacer(Modifier.height(12.dp))
                    Field("Licence number", ui.licenceNumber, KeyboardType.Text) {
                        onField(DriverField.LICENCE, it)
                    }
                    Spacer(Modifier.height(12.dp))
                    Field("Languages you speak (e.g. Tamil, English)", ui.languages, KeyboardType.Text) {
                        onField(DriverField.LANGUAGES, it)
                    }
                    Spacer(Modifier.height(20.dp))

                    WideButton(
                        if (ui.busy) "SAVING…" else "SAVE PROFILE",
                        palette.confirm,
                        enabled = !ui.busy && ui.canSave,
                        onClick = onSave
                    )
                    if (canCancel) {
                        Spacer(Modifier.height(12.dp))
                        WideButton("CANCEL", palette.muted, enabled = !ui.busy, onClick = onCancel)
                    }
                    Spacer(Modifier.height(12.dp))
                    WideButton("SIGN OUT", palette.danger, enabled = !ui.busy, onClick = onSignOut)
                }
            }

            if (ui.message.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    ui.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.clarify,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = LocalPalette.current.onBackground)
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalPalette.current.muted,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
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
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = if (selected) palette.confirm else palette.outline
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = if (selected) palette.confirm else palette.muted
        ),
        modifier = Modifier
            .width(140.dp)
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, accent, RoundedCornerShape(16.dp))
    ) {
        Text(if (selected) "● $label" else label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WideButton(label: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val colour = if (enabled) accent else palette.outline
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = colour,
            disabledContainerColor = palette.background,
            disabledContentColor = palette.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, colour, RoundedCornerShape(22.dp))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun SmallButton(label: String, accent: Color, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.background, contentColor = accent),
        modifier = Modifier.border(2.dp, palette.outline, RoundedCornerShape(14.dp))
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
