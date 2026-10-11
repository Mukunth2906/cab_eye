package com.cabeye.rider.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Turns the rider's back camera into a trickle of small JPEG pictures for the driver.
 *
 * ## Why pictures, not a video stream
 * The pictures travel through the backend over the ride connection both phones already hold,
 * which works on any network the ride itself works on — mobile data included, where two
 * phones usually cannot reach each other directly. A few small pictures a second is plenty to
 * answer "where is my passenger standing?", and it needs no video codec, relay server or
 * third-party service.
 *
 * ## One camera, three uses
 * The ride module and the explore module share this one camera session:
 *  - [jpegSink]: small JPEG pictures for the driver (ride module, only while shared);
 *  - [pictureSink]: each upright picture for the object detector, so the rider hears what is
 *    around them (both modules);
 *  - [attachPreview]: a live preview on the rider's own screen, for a low-vision rider or a helper.
 * The camera runs while any of them wants it; turning one off never cuts the others.
 *
 * ## What it deliberately does not do
 *  - **No audio.** It never touches the microphone, so the rider's app can keep listening for
 *    the driver to say the boarding code while the camera is on.
 *  - **No recording.** Each picture is encoded or looked at, handed over, and forgotten.
 *
 * The camera is bound to a private lifecycle owned by this class, not to the Activity, so
 * starting and stopping it is entirely the view model's decision. Call [start] and [stop] on
 * the main thread.
 */
class CameraStreamer(private val context: Context) {

    private companion object {
        const val TAG = "CabEye.Camera"

        /** Four pictures a second. Enough to follow someone turning; small enough for 3G. */
        const val FRAME_INTERVAL_MS = 250L

        /** Longest side of each picture sent to the driver, in pixels. */
        const val MAX_SIDE_PX = 480

        /** Longest side of each picture given to the detector: more detail than the driver needs. */
        const val PICTURE_SIDE_PX = 640

        const val JPEG_QUALITY = 50
        const val JPEG_QUALITY_SMALL = 35

        /** Above this many JPEG bytes the picture is re-encoded at the lower quality. */
        const val TARGET_MAX_BYTES = 38_000
    }

    /** A lifecycle this class alone drives, so CameraX starts and stops exactly when told. */
    private class StreamLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() {
            // A lifecycle may not jump straight from INITIALIZED to DESTROYED — which is exactly
            // what happens when stop() arrives before the camera finished opening.
            if (registry.currentState == Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.CREATED
            }
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private var owner: StreamLifecycle? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewSurface: Preview.SurfaceProvider? = null

    /** Ride module: base64 JPEG pictures for the driver, with their number. Background thread. */
    @Volatile
    var jpegSink: ((jpegBase64: String, n: Int) -> Unit)? = null

    /**
     * Each upright picture (longest side at most [PICTURE_SIDE_PX]), on a background thread. The
     * bitmap is recycled after the call returns: copy it to keep it.
     */
    @Volatile
    var pictureSink: ((Bitmap) -> Unit)? = null
    private var worker: ExecutorService? = null

    @Volatile private var lastFrameAt = 0L
    @Volatile private var frameCount = 0
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    /**
     * Opens the back camera and starts producing pictures.
     *
     * @param onFrame called on a background thread with each picture as base64 JPEG, and its number
     * @param onError called on the main thread if the camera cannot be opened or fails later.
     *   The streamer has already stopped itself by then.
     */
    fun start(onFrame: (jpegBase64: String, n: Int) -> Unit, onError: (reason: String) -> Unit) {
        jpegSink = onFrame
        start(onError)
    }

    /**
     * Shows the live camera in a PreviewView ([provider]), or stops showing it (null).
     * Main thread. Works before or after [start].
     */
    fun attachPreview(provider: Preview.SurfaceProvider?) {
        previewSurface = provider
        preview?.setSurfaceProvider(provider)
    }

    /** Opens the back camera (if it isn't already open) for whichever sinks are set. Main thread. */
    fun start(onError: (reason: String) -> Unit) {
        if (running) return
        running = true
        frameCount = 0
        lastFrameAt = 0L

        val lifecycle = StreamLifecycle().also { owner = it }
        val executor = Executors.newSingleThreadExecutor().also { worker = it }
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            // Stopped while the provider was still loading.
            if (!running || owner !== lifecycle) return@addListener
            try {
                val cameraProvider = future.get().also { provider = it }

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    // Only the newest picture matters; never build a backlog.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis = it }

                useCase.setAnalyzer(executor) { image -> handle(image) }

                val previewUseCase = Preview.Builder().build().also {
                    preview = it
                    it.setSurfaceProvider(previewSurface)
                }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, useCase, previewUseCase
                )
                lifecycle.resume()

                // A camera taken away later (another app, the screen locking) reports it here.
                // Recoverable errors CameraX retries by itself; only a critical one ends the
                // stream here. Anything quieter (the app sent to the background) is caught by
                // the view model's no-pictures watchdog.
                camera.cameraInfo.cameraState.observe(lifecycle) { state ->
                    val error = state.error
                    if (error != null && error.type == CameraState.ErrorType.CRITICAL && running) {
                        Log.w(TAG, "Camera error: code=${error.code}")
                        stop()
                        onError("CAMERA_ERROR")
                    }
                }
                Log.i(TAG, "Camera streaming started")
            } catch (e: Exception) {
                // No back camera, camera in use, or permission revoked between the check and now.
                Log.w(TAG, "Camera could not start: $e")
                stop()
                onError("CAMERA_UNAVAILABLE")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Turns the camera off. Safe to call at any time, any number of times. */
    fun stop() {
        if (!running && owner == null) return
        running = false
        analysis?.clearAnalyzer()
        runCatching { provider?.unbindAll() }
        owner?.destroy()
        owner = null
        analysis = null
        preview = null
        jpegSink = null
        pictureSink = null
        provider = null
        worker?.shutdown()
        worker = null
        Log.i(TAG, "Camera streaming stopped after $frameCount frame(s)")
    }

    /**
     * One camera image → one upright picture for the detector, and one small JPEG for the driver
     * (when shared), at most every [FRAME_INTERVAL_MS].
     */
    private fun handle(image: ImageProxy) {
        try {
            if (!running) return
            val jpegTo = jpegSink
            val pictureTo = pictureSink
            if (jpegTo == null && pictureTo == null) return
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            frameCount++

            val upright = scaleAndRotate(image.toBitmap(), image.imageInfo.rotationDegrees, PICTURE_SIDE_PX)
            if (pictureTo != null) runCatching { pictureTo(upright) }.onFailure { Log.w(TAG, "picture sink: $it") }
            if (jpegTo != null) {
                val small = if (max(upright.width, upright.height) > MAX_SIDE_PX) {
                    scaleAndRotate(upright, 0, MAX_SIDE_PX, recycleSource = false)
                } else upright
                var bytes = encode(small, JPEG_QUALITY)
                if (bytes.size > TARGET_MAX_BYTES) bytes = encode(small, JPEG_QUALITY_SMALL)
                if (small !== upright) small.recycle()
                jpegTo(Base64.encodeToString(bytes, Base64.NO_WRAP), frameCount)
            }
            upright.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Frame skipped: $e")
        } finally {
            image.close()
        }
    }

    private fun scaleAndRotate(source: Bitmap, rotationDegrees: Int, maxSide: Int, recycleSource: Boolean = true): Bitmap {
        val longest = max(source.width, source.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (recycleSource && out !== source) source.recycle()
        return out
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
}
