package com.cabeye.rider.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cabeye.rider.mindmap.Geo
import com.cabeye.rider.mindmap.MindMapCatalog
import com.cabeye.rider.mindmap.MindModule
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.LocalPalette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * The rider's mind map, on screen: everything it knows, in the two modules it came from, and what
 * each thing can do. Replaces the old voice-only panel.
 *
 *  - **From my rides**: cab destinations (visits, the rider's own name for them, distance,
 *    direction, area, cues felt on the way, walks from the drop-off, notes), saved multi-stop
 *    routes, ride cues and areas. Book a ride from any of them.
 *  - **From my walks**: spots the rider named while exploring, recorded and explored walks,
 *    personal notes, and what the camera saw nearby.
 *  - **Map**: a radar with the rider at the centre; tap a dot for its details.
 *
 * Every answer is spoken as well as shown ([answer]).
 */
@Composable
fun MindMapScreen(
    catalog: MindMapCatalog.Catalog?,
    answer: String,
    onLoad: () -> Unit,
    onClose: () -> Unit,
    onBook: (MindMapCatalog.PlaceCard) -> Unit,
    onExplain: (String) -> Unit,
    onForget: (String) -> Unit,
    onGuide: (String) -> Unit,
    onReadWalk: (String) -> Unit,
    onForgetWalk: (String) -> Unit,
    onBookRoute: (String) -> Unit,
    onExplore: () -> Unit,
    onQuick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { onLoad() }

    Column(modifier = modifier.fillMaxSize().background(palette.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("My mind map", style = MaterialTheme.typography.headlineSmall, color = palette.onBackground)
            Small("Close", palette.confirm, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("From my rides", "From my walks", "Map").forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(if (on) palette.surfaceElevated else palette.surface, RoundedCornerShape(12.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) palette.listening else palette.outline, RoundedCornerShape(12.dp))
                        .pointerInput(i) { detectTapGestures { tab = i } }
                        .semantics { contentDescription = "$label tab" + if (on) ", selected" else "" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) palette.listening else palette.muted, textAlign = TextAlign.Center)
                }
            }
        }

        if (answer.isNotBlank()) {
            Text(
                answer,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .background(palette.surface, CabEyeShapes.medium)
                    .border(1.5.dp, palette.listening, CabEyeShapes.medium)
                    .padding(12.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
        ) {
            if (catalog == null) {
                Text("Loading…", color = palette.muted, modifier = Modifier.padding(16.dp))
                return@Column
            }
            Text(catalog.summary, style = MaterialTheme.typography.bodyMedium, color = palette.muted, modifier = Modifier.padding(vertical = 8.dp))
            when (tab) {
                0 -> RidesTab(catalog, onBook, onExplain, onForget, onBookRoute, onQuick)
                1 -> WalksTab(catalog, onBook, onExplain, onForget, onGuide, onReadWalk, onForgetWalk, onExplore, onQuick)
                else -> MapTab(catalog, onExplain, onBook)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// =====================================================================================
//  From my rides
// =====================================================================================

@Composable
private fun RidesTab(
    c: MindMapCatalog.Catalog,
    onBook: (MindMapCatalog.PlaceCard) -> Unit,
    onExplain: (String) -> Unit,
    onForget: (String) -> Unit,
    onBookRoute: (String) -> Unit,
    onQuick: (String) -> Unit
) {
    val palette = LocalPalette.current
    Heading("Places you ride to (${c.ridePlaces.size})")
    if (c.ridePlaces.isEmpty()) {
        Empty("No ride destinations yet. They appear after your first ride. Hold the screen and say where you want to go.")
    }
    c.ridePlaces.forEach { p -> PlaceCardView(p, palette.listening, onBook, onExplain, onForget) }

    if (c.routes.isNotEmpty()) {
        Heading("Saved routes")
        c.routes.forEach { r ->
            CardBox(palette.processing) {
                Text(r.name, style = MaterialTheme.typography.titleLarge, color = palette.onBackground)
                Text((r.stops + r.destination).joinToString(" → "), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                if (r.uses > 0) Text("Used ${r.uses} times", style = MaterialTheme.typography.labelMedium, color = palette.muted)
                Spacer(Modifier.height(8.dp))
                Small("Book this route", palette.processing) { onBookRoute(r.name) }
            }
        }
    }

    if (c.rideNotes.isNotEmpty()) {
        Heading("Felt on rides, and areas you pass")
        c.rideNotes.forEach { n -> NoteRow(n, if (n.kind == NodeKind.AREA) palette.muted else palette.processing, onExplain, onForget) }
    }
    Spacer(Modifier.height(12.dp))
    Small("Famous places nearby", palette.confirm) { onQuick("what's famous around here") }
}

// =====================================================================================
//  From my walks
// =====================================================================================

@Composable
private fun WalksTab(
    c: MindMapCatalog.Catalog,
    onBook: (MindMapCatalog.PlaceCard) -> Unit,
    onExplain: (String) -> Unit,
    onForget: (String) -> Unit,
    onGuide: (String) -> Unit,
    onReadWalk: (String) -> Unit,
    onForgetWalk: (String) -> Unit,
    onExplore: () -> Unit,
    onQuick: (String) -> Unit
) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Wide("📷 Explore around me", palette.listening, Modifier.weight(1f), onClick = onExplore)
        Wide("Record a walk", palette.confirm, Modifier.weight(1f)) { onQuick("record my walk") }
    }

    Heading("Spots you named (${c.spots.size})")
    if (c.spots.isEmpty()) Empty("While exploring, tap Name this place, or say: this is Ravi's tea shop.")
    c.spots.forEach { p -> PlaceCardView(p, palette.confirm, onBook, onExplain, onForget) }

    Heading("Walks (${c.walks.size})")
    if (c.walks.isEmpty()) Empty("Record a walk, or explore with the camera and save it.")
    c.walks.forEach { w ->
        CardBox(palette.confirm) {
            Text(w.name, style = MaterialTheme.typography.titleLarge, color = palette.onBackground)
            Text(
                "${if (w.explored) "Explored" else "Walked"} ${w.walked}× · about ${w.metres} m · ${w.parts} parts · from ${w.from} · ${w.detail} directions",
                style = MaterialTheme.typography.bodyMedium, color = palette.muted
            )
            if (w.landmarks.isNotEmpty()) {
                Text("Landmarks: " + w.landmarks.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = palette.onBackground)
            }
            if (w.checkFor.isNotEmpty()) {
                Text("Check these are still there: " + w.checkFor.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = palette.clarify)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("Guide me", palette.confirm) { onGuide(w.routeId) }
                Small("Read route", palette.listening) { onReadWalk(w.routeId) }
                Small("Forget", palette.danger) { onForgetWalk(w.routeId) }
            }
        }
    }

    if (c.notes.isNotEmpty()) {
        Heading("Your notes")
        c.notes.forEach { n -> NoteRow(n, palette.clarify, onExplain, onForget) }
    }

    Heading("Seen with your camera (${c.sightings.size})")
    if (c.sightings.isEmpty()) Empty("Explore with the camera: everything it notices is kept here, with where you saw it.")
    c.sightings.forEach { s ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(palette.surface, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(s.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, color = palette.onBackground)
                Text(
                    listOfNotNull(
                        "seen ${s.times}×",
                        s.side.takeIf { it.isNotBlank() },
                        s.distanceM?.let { "${Geo.spokenDistance(it)} ${s.direction}" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = palette.muted
                )
            }
            Small("Forget", palette.danger) { onForget(s.nodeId) }
        }
    }
}

// =====================================================================================
//  Map: a radar with the rider at the centre
// =====================================================================================

@Composable
private fun MapTab(c: MindMapCatalog.Catalog, onExplain: (String) -> Unit, onBook: (MindMapCatalog.PlaceCard) -> Unit) {
    val palette = LocalPalette.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    if (c.centre == null || c.dots.isEmpty()) {
        Empty("Nothing to place on the map yet. Places appear here once they have a location.")
        return
    }
    val maxD = c.dots.maxOf { it.distanceM }.coerceAtLeast(200.0)
    fun radius(d: Double, r: Float) = (ln(1 + d) / ln(1 + maxD)).toFloat() * r * 0.92f

    Text("Centre: ${if (c.ridePlaces.isNotEmpty() && c.centre == c.ridePlaces.firstOrNull()?.let { it.lat to it.lng }) c.ridePlaces.first().name else "you"} · up is north · tap a dot",
        style = MaterialTheme.typography.bodyMedium, color = palette.muted)
    Spacer(Modifier.height(6.dp))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(palette.surface, RoundedCornerShape(16.dp))
            .pointerInput(c.dots) {
                detectTapGestures { tap ->
                    val r = min(size.width, size.height) / 2f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    selected = c.dots.minByOrNull { d ->
                        val rr = radius(d.distanceM, r)
                        val a = Math.toRadians(d.bearing)
                        hypot(cx + rr * sin(a).toFloat() - tap.x, cy - rr * cos(a).toFloat() - tap.y)
                    }?.nodeId
                }
            }
            .semantics {
                contentDescription = "Map of ${c.dots.size} places and things around you. " +
                    c.dots.sortedBy { it.distanceM }.take(8).joinToString("; ") {
                        "${it.label}, ${Geo.spokenDistance(it.distanceM)} ${Geo.compass(it.bearing)}"
                    }
            }
    ) {
        val r = min(size.width, size.height) / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val text = android.graphics.Paint().apply { isAntiAlias = true; textSize = 12.dp.toPx(); color = android.graphics.Color.LTGRAY }
        for (ring in listOf(100.0, 1_000.0, 5_000.0).filter { it < maxD * 1.2 }) {
            val rr = radius(ring, r)
            drawCircle(Color(0x334B5563), rr, centre, style = Stroke(1.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(Geo.spokenDistance(ring).removePrefix("about "), centre.x + 4, centre.y - rr - 2, text)
        }
        drawCircle(Color.White, 7.dp.toPx(), centre)
        for (d in c.dots) {
            val rr = radius(d.distanceM, r)
            val a = Math.toRadians(d.bearing)
            val p = Offset(centre.x + rr * sin(a).toFloat(), centre.y - rr * cos(a).toFloat())
            val (color, size) = when {
                d.kind == NodeKind.OBSERVED -> Color(0xFFFBBF24) to 4.dp.toPx()
                d.kind == NodeKind.RIDE_CUE -> Color(0xFFA855F7) to 4.dp.toPx()
                d.module == MindModule.EXPLORE -> Color(0xFF22C55E) to 7.dp.toPx()
                else -> Color(0xFF38BDF8) to 8.dp.toPx()
            }
            drawCircle(color, if (d.nodeId == selected) size * 1.8f else size, p)
            if (d.kind == NodeKind.PLACE || d.kind == NodeKind.ANCHOR) {
                text.color = android.graphics.Color.WHITE
                drawContext.canvas.nativeCanvas.drawText(d.label.take(18), p.x + 8.dp.toPx(), p.y + 4.dp.toPx(), text)
                text.color = android.graphics.Color.LTGRAY
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Legend(Color(0xFF38BDF8), "Ride places")
        Legend(Color(0xFF22C55E), "Named spots")
        Legend(Color(0xFFFBBF24), "Camera")
        Legend(Color(0xFFA855F7), "Ride cues")
    }
    val sel = c.dots.firstOrNull { it.nodeId == selected }
    if (sel != null) {
        val card = (c.ridePlaces + c.spots).firstOrNull { it.nodeId == sel.nodeId }
        CardBox(palette.listening) {
            Text(sel.label, style = MaterialTheme.typography.titleLarge, color = palette.onBackground)
            Text("${Geo.spokenDistance(sel.distanceM)} ${Geo.compass(sel.bearing)}", color = palette.muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("What's around", palette.listening) { onExplain(sel.nodeId) }
                if (card != null) Small("Book a ride", palette.confirm) { onBook(card) }
            }
        }
    }
}

// =====================================================================================
//  Pieces
// =====================================================================================

@Composable
private fun PlaceCardView(
    p: MindMapCatalog.PlaceCard,
    accent: Color,
    onBook: (MindMapCatalog.PlaceCard) -> Unit,
    onExplain: (String) -> Unit,
    onForget: (String) -> Unit
) {
    val palette = LocalPalette.current
    CardBox(accent) {
        Text(p.name, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(
                p.kindLabel,
                p.visits.takeIf { it > 0 }?.let { "$it visits" },
                p.distanceM?.let { "${Geo.spokenDistance(it)} ${p.direction}" },
                p.area.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = palette.muted
        )
        if (p.aliases.isNotEmpty()) Line("You call it: " + p.aliases.joinToString(", "))
        p.notes.forEach { Line("Note: $it", palette.clarify) }
        if (p.walks.isNotEmpty()) Line("Walk from here: " + p.walks.joinToString(", "), palette.confirm)
        if (p.cues.isNotEmpty()) Line("On the way: " + p.cues.joinToString(", "), palette.processing)
        if (p.seenHere.isNotEmpty()) Line("Camera saw here: " + p.seenHere.joinToString(", "), Color(0xFFFBBF24))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.lat != null) Small("Book a ride", palette.confirm) { onBook(p) }
            Small("What's around", palette.listening) { onExplain(p.nodeId) }
            Small("Forget", palette.danger) { onForget(p.nodeId) }
        }
    }
}

@Composable
private fun NoteRow(n: MindMapCatalog.NoteCard, accent: Color, onExplain: (String) -> Unit, onForget: (String) -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(palette.surface, RoundedCornerShape(12.dp))
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.titleMedium, color = palette.onBackground)
            if (n.detail.isNotBlank()) Text(n.detail, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Small("Hear", palette.listening) { onExplain(n.nodeId) }
            Spacer(Modifier.height(4.dp))
            Small("Forget", palette.danger) { onForget(n.nodeId) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    val palette = LocalPalette.current
    Spacer(Modifier.height(14.dp))
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = palette.muted)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Empty(text: String) {
    val palette = LocalPalette.current
    Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.muted, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Line(text: String, color: Color = LocalPalette.current.onBackground) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).background(color, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.muted)
    }
}

@Composable
private fun CardBox(accent: Color, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(palette.surface, CabEyeShapes.medium)
            .border(2.dp, accent, CabEyeShapes.medium)
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun Small(label: String, accent: Color, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.background, contentColor = accent),
        modifier = Modifier.heightIn(min = 48.dp).border(1.5.dp, accent, RoundedCornerShape(12.dp))
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Wide(label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.surface, contentColor = accent),
        modifier = modifier.heightIn(min = 56.dp).border(2.dp, accent, RoundedCornerShape(14.dp))
    ) { Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}
