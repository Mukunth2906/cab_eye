package com.cabeye.rider.vision

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands the next camera picture to whoever asks for one — the ride camera's detector, or the
 * explore module — without copying every picture the camera produces. Set [sink] as the camera's
 * picture sink; call [next] to wait for one.
 */
class FrameTap {

    private val waiting = AtomicReference<CompletableDeferred<Bitmap>?>(null)

    /** The camera's picture sink: copies a picture only when someone is waiting for it. */
    val sink: (Bitmap) -> Unit = { picture ->
        waiting.getAndSet(null)?.let { d ->
            runCatching { picture.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()?.let(d::complete)
        }
    }

    /** The next picture, or null if none arrives within [timeoutMs] (camera closed, app paused). */
    suspend fun next(timeoutMs: Long = 3_000): Bitmap? {
        val d = CompletableDeferred<Bitmap>()
        waiting.getAndSet(d)?.cancel()
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { if (it == null) waiting.compareAndSet(d, null) }
    }
}
