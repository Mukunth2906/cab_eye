package com.cabeye.rider.ui

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cabeye.rider.explore.ExploreMode
import com.cabeye.rider.explore.ExploreUi
import com.cabeye.rider.ui.components.DetectionOverlay
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.vision.SceneSource

/**
 * The explore module's screen: the live camera with what it finds drawn on it, and big buttons.
 *
 * Built for three people at once: the blind rider (everything is spoken, and holding the camera
 * picture or a volume button talks — "find the door", "this is Ravi's tea shop"), a low-vision rider
 * (large boxes and words), and a sighted helper (every action is a button).
 */
@Composable
fun ExploreScreen(
    ui: ExploreUi,
    onPreview: (Preview.SurfaceProvider?) -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onDescribe: () -> Unit,
    onRead: () -> Unit,
    onKeepTelling: () -> Unit,
    onFind: (String) -> Unit,
    onStop: () -> Unit,
    onName: (String) -> Unit,
    onAskName: () -> Unit,
    onFinish: (save: Boolean) -> Unit,
    micOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    var showFind by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(previewView) {
        onPreview(previewView.surfaceProvider)
        onDispose { onPreview(null) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // ---- the camera, with boxes; holding it talks -----------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        holdStart()
                        tryAwaitRelease()
                        holdEnd()
                    })
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Camera. Hold here and speak: describe, find the door, read this, " +
                        "or this is, and a name. " + ui.lastSaid
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            DetectionOverlay(objects = ui.live, imageAspect = 3f / 4f, modifier = Modifier.fillMaxSize())

            // Status strip at the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        micOpen -> "◉ LISTENING — let go when done"
                        ui.busy -> "LOOKING…"
                        ui.mode == ExploreMode.CONTINUOUS -> "● TELLING YOU AS YOU WALK"
                        ui.mode == ExploreMode.FINDING -> "● FINDING: ${ui.findTarget.uppercase()}"
                        else -> "EXPLORE"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (micOpen) palette.listening else palette.onBackground
                )
                Text(
                    buildString {
                        append("${ui.seen} seen")
                        if (ui.spots.isNotEmpty()) append(" · ${ui.spots.size} named")
                        if (ui.recordingPath) append(" · path ●")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted
                )
            }

            // What was last said, large, at the bottom of the picture.
            if (ui.lastSaid.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xDD000000))
                        .padding(12.dp)
                ) {
                    Text(ui.lastSaid, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    ui.scene?.source?.let { src ->
                        Text(
                            if (src == SceneSource.GEMINI) "Described by Gemini" else "Detected on this phone (offline)",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted
                        )
                    }
                }
            } else {
                Text(
                    "Hold here and speak, or use the buttons",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(10.dp)
                )
            }
        }

        // ---- the buttons -------------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            if (naming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(60) },
                        label = { Text("Name this place (e.g. Ravi's tea shop)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.onBackground, unfocusedTextColor = palette.onBackground,
                            focusedBorderColor = palette.clarify, unfocusedBorderColor = palette.outline,
                            focusedLabelColor = palette.clarify, unfocusedLabelColor = palette.muted
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    BigButton("Save", palette.confirm) {
                        if (nameDraft.isNotBlank()) onName(nameDraft)
                        nameDraft = ""
                        naming = false
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (showFind) {
                Text("Find:", style = MaterialTheme.typography.labelLarge, color = palette.onBackground)
                Spacer(Modifier.height(6.dp))
                listOf("door", "steps", "seat", "person", "vehicle", "bus stop", "table", "text").chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { t ->
                            BigButton(t.replaceFirstChar { it.uppercase() }, palette.clarify, Modifier.weight(1f)) {
                                showFind = false
                                onFind(t)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton("Describe", palette.listening, Modifier.weight(1f), onClick = onDescribe)
                BigButton(
                    if (ui.mode == ExploreMode.CONTINUOUS) "Stop telling" else "Keep telling me",
                    palette.listening, Modifier.weight(1f), onClick = onKeepTelling
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (ui.mode == ExploreMode.FINDING) {
                    BigButton("Stop finding", palette.clarify, Modifier.weight(1f), onClick = onStop)
                } else {
                    BigButton("Find…", palette.clarify, Modifier.weight(1f)) { showFind = !showFind }
                }
                BigButton("Read text", palette.clarify, Modifier.weight(1f), onClick = onRead)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton("Name this place", palette.confirm, Modifier.weight(1f)) {
                    naming = !naming
                    if (naming) onAskName()
                }
                BigButton("Save & finish", palette.confirm, Modifier.weight(1f)) { onFinish(true) }
            }
            Spacer(Modifier.height(8.dp))
            BigButton("Close without saving the walk", palette.muted, Modifier.fillMaxWidth()) { onFinish(false) }
        }
    }
}

@Composable
private fun BigButton(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.surface, contentColor = accent),
        modifier = modifier
            .heightIn(min = 56.dp)
            .border(2.dp, accent, RoundedCornerShape(16.dp))
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
