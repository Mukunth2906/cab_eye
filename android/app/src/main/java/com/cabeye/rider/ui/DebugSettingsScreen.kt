package com.cabeye.rider.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.net.BackendUrl
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.MinTouchTarget

/**
 * The debug settings screen: one text field, one test button, one role toggle.
 *
 * ## Why it is this small
 * Everything here is a developer or demo affordance, and the rider never opens it — the backend
 * URL is baked into the APK at build time precisely so that a person who cannot see the screen
 * never has to configure anything. So this screen optimises for the person running the demo,
 * not for the rider: it is dense, it shows the derived socket URL, and it says exactly what
 * happened rather than a friendly summary.
 *
 * ## What "Test connection" actually does
 * It calls `/health` and **speaks the result**. Aloud, not as a toast. That is not a flourish:
 * the person most likely to have pasted the wrong URL is standing next to the phone, and a
 * spoken answer works whether or not they are looking at it — and it exercises the TTS path at
 * the same time, so a silent phone is diagnosed here rather than mid-ride.
 *
 * @param onTestConnection performs the ping; the result is spoken by the caller and returned
 *   here as text so the screen can show it too
 */
@Composable
fun DebugSettingsScreen(
    currentUrl: String,
    currentRole: AppRole,
    debugBuild: Boolean,
    onSave: (String) -> Unit,
    onTestConnection: (onResult: (String) -> Unit) -> Unit,
    onRoleChange: (AppRole) -> Unit,
    onClose: () -> Unit,
    signedInAs: String = "",
    onSignOut: (() -> Unit)? = null,
    /** Rider only: null hides the camera setting. */
    cameraAlwaysShare: Boolean? = null,
    onCameraAlwaysShare: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current

    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    // Live preview of exactly what will be saved. The whole class of "it looks right in the box
    // but does not work" failures comes from invisible characters — a trailing slash, a newline
    // a terminal copy brought along — so the normalised form is shown before anything is saved.
    val normalised = BackendUrl.normaliseBase(text)
    val socketPreview = normalised?.let { BackendUrl.toSocketBase(it) + "/ws/ride" } ?: "—"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = palette.onBackground)
                SmallButton("Done", palette.confirm, onClose)
            }

            Spacer(Modifier.height(24.dp))

            // ---------------------------------------------------------------------------
            //  Backend address
            // ---------------------------------------------------------------------------

            Text("Backend address", style = MaterialTheme.typography.bodyLarge, color = palette.clarify)
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste the https address. The socket address is worked out from it — " +
                        "you never type both.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("https://something.ngrok-free.app", color = palette.muted) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    // No autocorrect and no capitalisation. A URL "corrected" into
                    // "Https://Abc123..." is a URL that fails, and the failure looks like a
                    // dead backend rather than like a typo.
                    autoCorrect = false,
                    imeAction = ImeAction.Done
                ),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = palette.onBackground,
                    unfocusedTextColor = palette.onBackground,
                    focusedContainerColor = palette.background,
                    unfocusedContainerColor = palette.background,
                    focusedIndicatorColor = palette.listening,
                    unfocusedIndicatorColor = palette.outline,
                    cursorColor = palette.listening
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Will save as:  ${normalised ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (normalised == null) palette.danger else palette.muted
            )
            Text(
                "Socket:  $socketPreview",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted
            )

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                WideButton(
                    label = "Save",
                    accent = palette.confirm,
                    modifier = Modifier.weight(1f),
                    enabled = normalised != null
                ) {
                    normalised?.let {
                        onSave(it)
                        status = "Saved. Now pointing at ${BackendUrl.spokenHost(it)}"
                    }
                }
                Spacer(Modifier.padding(horizontal = 6.dp))
                WideButton(
                    label = if (testing) "Testing…" else "Test connection",
                    accent = palette.listening,
                    modifier = Modifier.weight(1f),
                    enabled = !testing
                ) {
                    testing = true
                    status = "Testing…"
                    onTestConnection { result ->
                        status = result
                        testing = false
                    }
                }
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackground
                )
            }

            Spacer(Modifier.height(32.dp))
            Divider()
            Spacer(Modifier.height(24.dp))

            // ---------------------------------------------------------------------------
            //  Role
            // ---------------------------------------------------------------------------

            Text("Mode", style = MaterialTheme.typography.bodyLarge, color = palette.clarify)
            Spacer(Modifier.height(4.dp))
            Text(
                // Said plainly, because it is the surprising part: this toggle does not merely
                // change the screen. It releases the microphone and the speech engine.
                "Switching to Driver releases the microphone and shuts the narrator down — " +
                        "not just hides them.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted
            )
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth()) {
                RoleButton(
                    label = "RIDER",
                    role = AppRole.RIDER,
                    current = currentRole,
                    modifier = Modifier.weight(1f),
                    onClick = onRoleChange
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                RoleButton(
                    label = "DRIVER",
                    role = AppRole.DRIVER,
                    current = currentRole,
                    modifier = Modifier.weight(1f),
                    onClick = onRoleChange
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Every request carries X-Role: ${currentRole.wireName}",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted
            )

            if (!debugBuild) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "This is a release build. The role toggle is a debug affordance and " +
                            "would not ship in this form.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.danger
                )
            }

            if (onSignOut != null) {
                Spacer(Modifier.height(28.dp))
                Divider()
                Spacer(Modifier.height(20.dp))
                Text("Account", style = MaterialTheme.typography.bodyLarge, color = palette.clarify)
                Spacer(Modifier.height(4.dp))
                Text(
                    signedInAs.ifBlank { "Not signed in" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted
                )
                Spacer(Modifier.height(12.dp))
                WideButton("SIGN OUT", palette.danger) { onSignOut() }
            }

            if (cameraAlwaysShare != null) {
                Spacer(Modifier.height(28.dp))
                Divider()
                Spacer(Modifier.height(20.dp))
                Text("Camera for your driver", style = MaterialTheme.typography.bodyLarge, color = palette.clarify)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (cameraAlwaysShare)
                        "Shared automatically when your driver can't find you. Tap to be asked each time."
                    else
                        "You are asked each time your driver wants to see your camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted
                )
                Spacer(Modifier.height(12.dp))
                WideButton(
                    if (cameraAlwaysShare) "ASK ME EACH TIME" else "ALWAYS SHARE",
                    palette.clarify
                ) { onCameraAlwaysShare(!cameraAlwaysShare) }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Divider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(palette.outline)
    )
}

@Composable
private fun RoleButton(
    label: String,
    role: AppRole,
    current: AppRole,
    modifier: Modifier = Modifier,
    onClick: (AppRole) -> Unit
) {
    val palette = LocalPalette.current
    val selected = role == current
    val accent = if (selected) palette.confirm else palette.muted

    Button(
        onClick = { onClick(role) },
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) palette.confirm.copy(alpha = 0.15f) else palette.background,
            contentColor = accent
        ),
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, accent, RoundedCornerShape(18.dp))
    ) {
        Text(
            // A tick as well as a colour, so selection never depends on hue alone.
            text = if (selected) "✓ $label" else label,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WideButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    val shown = if (enabled) accent else palette.muted

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = shown,
            disabledContainerColor = palette.background,
            disabledContentColor = palette.muted
        ),
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, shown, RoundedCornerShape(18.dp))
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SmallButton(label: String, accent: Color, onClick: () -> Unit) {
    val palette = LocalPalette.current

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = accent
        ),
        modifier = Modifier.border(2.dp, accent, RoundedCornerShape(14.dp))
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
