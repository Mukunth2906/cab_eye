package com.cabeye.rider.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Object detection on the phone itself (Google ML Kit, models bundled in the app): works with no
 * network and no key. It finds where things are (boxes) and names each one by labelling just that
 * part of the picture, so the rider hears "a chair at 1 o'clock" rather than a list of words.
 * Also reads printed text for "read this".
 *
 * Simpler than Gemini — no distances, coarser names — so it is the fallback for descriptions and
 * the fast detector for "keep telling me", which runs on many pictures a minute.
 */
class OnDeviceDetector {

    private val objects = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Labels too vague to tell a blind walker anything. */
    private val VAGUE = setOf(
        "home good", "fashion good", "place", "food", "plant", "room", "interior design", "pattern",
        "darkness", "sky", "event", "fun", "metal", "wood", "font", "material", "textile", "smile"
    )

    suspend fun detect(bitmap: Bitmap, readText: Boolean = false, now: Long = System.currentTimeMillis()): Scene {
        val image = InputImage.fromBitmap(bitmap, 0)
        val found = runCatching { objects.process(image).await() }.getOrElse {
            Log.w(TAG, "object detection failed: ${it.message}"); emptyList()
        }
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val named = mutableListOf<SceneObject>()
        for (o in found.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(5)) {
            val r = o.boundingBox
            val label = nameOf(bitmap, r) ?: o.labels.maxByOrNull { it.confidence }?.text
                ?.lowercase()?.takeUnless { it in VAGUE } ?: continue
            val box = Box(r.left / w, r.top / h, r.right / w, r.bottom / h)
            named += SceneObject(label = label, box = box, clock = SceneSpeech.clock(box.centerX), confidence = 0.6f)
        }

        // The whole picture's labels: what kind of place this is, and things the boxes missed.
        val scene = runCatching { labeler.process(image).await() }.getOrElse { emptyList() }
            .map { it.text.lowercase() }.filter { it !in VAGUE }
        val extra = scene.filter { l -> named.none { it.label == l } }.take(3)

        val text = if (readText) runCatching { reader.process(image).await().text }.getOrDefault("").trim() else ""
        val summary = when {
            named.isEmpty() && extra.isEmpty() -> ""
            else -> "Around you: " + (named.map { it.label } + extra).distinct().take(5).joinToString(", ") + "."
        }
        return Scene(summary = summary, objects = named, text = text, source = SceneSource.ON_DEVICE, at = now)
    }

    /** Text only, for "read this" with no Gemini. */
    suspend fun read(bitmap: Bitmap): String =
        runCatching { reader.process(InputImage.fromBitmap(bitmap, 0)).await().text }.getOrDefault("").trim()

    /** Labels just the part of the picture in [r]: the best specific name, or null. */
    private suspend fun nameOf(bitmap: Bitmap, r: Rect): String? {
        val pad = (max(r.width(), r.height()) * 0.1f).toInt()
        val left = max(0, r.left - pad)
        val top = max(0, r.top - pad)
        val right = min(bitmap.width, r.right + pad)
        val bottom = min(bitmap.height, r.bottom + pad)
        if (right - left < 24 || bottom - top < 24) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            labeler.process(InputImage.fromBitmap(crop, 0)).await()
                .map { it.text.lowercase() to it.confidence }
                .filter { it.first !in VAGUE }
                .maxByOrNull { it.second }?.first
        } catch (e: Exception) {
            null
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    fun close() {
        runCatching { objects.close() }
        runCatching { labeler.close() }
        runCatching { reader.close() }
    }

    private companion object {
        const val TAG = "CabEye.Vision"
    }
}

/** A Play services task as a suspending call. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
