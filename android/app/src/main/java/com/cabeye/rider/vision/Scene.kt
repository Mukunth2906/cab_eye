package com.cabeye.rider.vision

import org.json.JSONObject

/** Where a scene description came from. */
enum class SceneSource(val spoken: String) {
    /** Gemini, through the backend: rich descriptions, text reading. */
    GEMINI("Gemini"),
    /** The phone's own detector (ML Kit): works offline, simpler labels. */
    ON_DEVICE("on this phone")
}

/** A box on the picture, each side as a fraction 0..1 of the picture's width or height. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

/**
 * One thing in front of the rider.
 *
 * @param clock where it is, as a clock position ("12 o'clock" = straight ahead)
 * @param distanceM a rough distance, when the detector could estimate one
 */
data class SceneObject(
    val label: String,
    val box: Box? = null,
    val clock: String = "",
    val distanceM: Double? = null,
    val confidence: Float = 1f
)

/**
 * What the camera saw in one picture.
 *
 * @param found for "find the door": a sentence saying whether and where it is
 */
data class Scene(
    val summary: String = "",
    val objects: List<SceneObject> = emptyList(),
    val hazards: List<String> = emptyList(),
    val text: String = "",
    val found: String = "",
    val source: SceneSource = SceneSource.ON_DEVICE,
    val at: Long = 0
) {
    val isEmpty: Boolean get() = summary.isBlank() && objects.isEmpty() && hazards.isEmpty() && text.isBlank()

    companion object {
        /** The backend's `/me/vision/describe` reply. Boxes there are [ymin, xmin, ymax, xmax] in 0..1000. */
        fun fromServer(o: JSONObject, at: Long): Scene {
            val objects = mutableListOf<SceneObject>()
            val arr = o.optJSONArray("objects")
            for (i in 0 until (arr?.length() ?: 0)) {
                val n = arr!!.optJSONObject(i) ?: continue
                val label = n.optString("label").trim()
                if (label.isEmpty()) continue
                val b = n.optJSONArray("box")
                val box = if (b != null && b.length() == 4) {
                    Box(
                        left = (b.optDouble(1) / 1000).toFloat(), top = (b.optDouble(0) / 1000).toFloat(),
                        right = (b.optDouble(3) / 1000).toFloat(), bottom = (b.optDouble(2) / 1000).toFloat()
                    )
                } else null
                objects += SceneObject(
                    label = label,
                    box = box,
                    clock = n.optString("clock").ifBlank { box?.let { SceneSpeech.clock(it.centerX) }.orEmpty() },
                    distanceM = if (n.has("distanceM") && !n.isNull("distanceM")) n.optDouble("distanceM") else null
                )
            }
            val hz = o.optJSONArray("hazards")
            return Scene(
                summary = o.optString("summary"),
                objects = objects,
                hazards = (0 until (hz?.length() ?: 0)).map { hz!!.optString(it) }.filter { it.isNotBlank() },
                text = o.optString("text"),
                found = o.optString("found"),
                source = SceneSource.GEMINI,
                at = at
            )
        }
    }
}
