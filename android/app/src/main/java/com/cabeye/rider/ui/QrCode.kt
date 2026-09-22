package com.cabeye.rider.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code, drawn black on white whatever the app theme is.
 *
 * Always black-on-white with its own quiet zone: phone cameras read dark modules on a light
 * ground, and the rider's high-contrast themes (yellow on black) would otherwise produce a
 * code that looks fine and scans as nothing.
 *
 * Encoded once per [content] and cached, so recomposition during the payment poll does not
 * re-run the encoder every two seconds.
 *
 * @param description what TalkBack reads. A QR code is pure pixels, so without this it is
 *   announced as "image" — useless to the one user who most needs to know what it is for.
 */
@Composable
fun QrCode(content: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { encodeQr(content, QR_PIXELS) }

    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(8.dp)
            .semantics { contentDescription = description }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                // Nearest-neighbour: smoothing blurs module edges and costs scan reliability.
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(size)
            )
        }
    }
}

/** @return null only if ZXing refuses the input (for example, content far too long). */
internal fun encodeQr(content: String, pixels: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val w = matrix.width
    val h = matrix.height
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            out[row + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()

private const val QR_PIXELS = 512
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
