package com.cabeye.rider.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabeye.rider.vision.SceneObject
import com.cabeye.rider.vision.SceneSpeech

/**
 * Boxes and names over a camera picture: what the detector found, where. Shared by the rider's
 * explore screen and ride preview, and the driver's view of the rider's camera — so a helper,
 * the rider and the driver all see the same things the rider hears.
 *
 * @param imageAspect width / height of the picture as it is fitted ("Fit") inside this overlay
 */
@Composable
fun DetectionOverlay(objects: List<SceneObject>, imageAspect: Float, modifier: Modifier = Modifier) {
    val described = objects.filter { it.box != null }
    Canvas(
        modifier = modifier.semantics {
            contentDescription = if (described.isEmpty()) "Nothing detected yet"
            else "Detected: " + described.joinToString(", ") { "${it.label} at ${it.clock}" }
        }
    ) {
        if (described.isEmpty()) return@Canvas
        // Where the fitted picture sits inside this canvas.
        val canvasAspect = size.width / size.height
        val (w, h) = if (imageAspect > canvasAspect) size.width to size.width / imageAspect
        else size.height * imageAspect to size.height
        val ox = (size.width - w) / 2
        val oy = (size.height - h) / 2

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 15.dp.toPx()
            isFakeBoldText = true
        }
        val labelBg = android.graphics.Paint().apply { isAntiAlias = true }

        for (o in described) {
            val b = o.box!!
            val hazard = SceneSpeech.isHazard(o.label)
            val color = if (hazard) Color(0xFFEF4444) else Color(0xFF38BDF8)
            val left = ox + b.left.coerceIn(0f, 1f) * w
            val top = oy + b.top.coerceIn(0f, 1f) * h
            val right = ox + b.right.coerceIn(0f, 1f) * w
            val bottom = oy + b.bottom.coerceIn(0f, 1f) * h
            drawRect(color, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 3.dp.toPx()))

            val text = if (o.clock.isNotBlank()) "${o.label} · ${o.clock}" else o.label
            val tw = paint.measureText(text)
            val th = paint.textSize
            val ty = (top - 4.dp.toPx()).coerceAtLeast(th + 4.dp.toPx())
            labelBg.color = android.graphics.Color.argb(220, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            drawContext.canvas.nativeCanvas.drawRect(left, ty - th - 2.dp.toPx(), left + tw + 8.dp.toPx(), ty + 4.dp.toPx(), labelBg)
            paint.color = android.graphics.Color.BLACK
            drawContext.canvas.nativeCanvas.drawText(text, left + 4.dp.toPx(), ty, paint)
        }
    }
}
