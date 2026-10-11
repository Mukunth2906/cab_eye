package com.cabeye.rider.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.RideApi
import java.io.ByteArrayOutputStream

/**
 * One place to ask "what's in front of me", used by both camera modules (the ride camera and
 * explore).
 *
 *  - [describe] (a press, or every few seconds while exploring): Gemini through the backend when
 *    it is set up and reachable — richer names, distances, hazards, reading text — and the phone's
 *    own detector otherwise. Falling back is silent and per picture: a rate limit or a dropped
 *    connection costs one picture's detail, never the answer.
 *  - [quick]: always on the phone, for the many pictures a minute of "keep telling me" and of the
 *    ride camera, where waiting on a network round trip would describe a scene already gone.
 */
class SceneDetector(private val api: RideApi) {

    private val onDevice by lazy { OnDeviceDetector() }

    /** Null until the backend has answered once; false after it says it has no Gemini key. */
    @Volatile
    var geminiAvailable: Boolean? = null
        private set

    enum class Mode { DESCRIBE, FIND, READ }

    suspend fun describe(bitmap: Bitmap, mode: Mode = Mode.DESCRIBE, target: String = ""): Scene {
        if (geminiAvailable != false) {
            when (val r = api.visionDescribe(jpeg(bitmap), mode.name, target)) {
                is ApiResult.Ok -> {
                    geminiAvailable = true
                    return r.value
                }
                is ApiResult.Failed -> {
                    if (r.detail.startsWith("HTTP 404")) geminiAvailable = false
                    Log.i(TAG, "Gemini not used for this picture (${r.detail.take(40)}); on-device instead")
                }
            }
        }
        val scene = onDevice.detect(bitmap, readText = mode != Mode.FIND)
        return if (mode == Mode.FIND) scene.copy(found = SceneSpeech.find(SceneSpeech.targetOf(target), scene)) else scene
    }

    suspend fun quick(bitmap: Bitmap): Scene = onDevice.detect(bitmap, readText = false)

    fun close() = onDevice.close()

    private fun jpeg(bitmap: Bitmap): String = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val TAG = "CabEye.Vision"
    }
}
