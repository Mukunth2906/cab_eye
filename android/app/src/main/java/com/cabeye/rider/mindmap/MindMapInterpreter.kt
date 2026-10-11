package com.cabeye.rider.mindmap

import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.LandmarkParser
import com.cabeye.rider.walk.Side
import org.json.JSONObject

/**
 * The optional free-form reader, phone side.
 *
 * When the rules ([AnchorParser], [RideCueParser]) don't understand a sentence and the rider has
 * agreed, that one sentence goes to the backend (`POST /me/mindmap/interpret`), which asks
 * Grok to read it into the same shape the rules produce. The answer is checked here again by
 * the same rules as the rider's own words: a visual-only subject is refused, a claim that a way
 * is clear is dropped, and an unsure reading is asked about rather than saved.
 *
 * Pure (org.json only), so the checks are JVM tests.
 */
object MindMapInterpreter {

    /** Below this, the agent asks "Did you mean …?" before saving. */
    const val SURE = 0.7

    sealed interface Reading {
        data class Note(val anchor: AnchorParser.Anchor, val confidence: Double) : Reading
        data class Cue(val words: String, val confidence: Double) : Reading
        data object Nothing : Reading
    }

    private val CLEAR = Regex("\\b(clear|safe to (cross|walk|go)|no obstacles?)\\b", RegexOption.IGNORE_CASE)

    /** What to ask the server: the sentence, and a few names the rider already knows. */
    fun request(utterance: String, map: MindMap, near: Node?): JSONObject {
        val names = buildList {
            near?.let { add(it.label) }
            near?.let { n -> map.neighbours(n.id).forEach { add(it.label) } }
            map.nodes.filter { it.kind == NodeKind.PLACE || it.kind == NodeKind.ANCHOR }.sortedByDescending { it.lastConfirmedAt }
                .forEach { add(it.label) }
        }.filter { it.isNotBlank() }.distinct().take(20)
        return JSONObject().put("utterance", utterance.take(300)).put("known", org.json.JSONArray(names))
    }

    fun read(json: String, words: String): Reading {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Reading.Nothing
        val intent = o.optString("intent").uppercase()
        val subjectRaw = o.optString("subject").trim().lowercase()
        val confidence = o.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) }
        if (subjectRaw.isBlank()) return Reading.Nothing
        // The same refusal as the rider's own words: a sign or a colour is not a landmark.
        val lp = LandmarkParser.parse(subjectRaw)
        if (lp is LandmarkParser.Result.VisualOnly && !subjectRaw.contains("'s ")) return Reading.Nothing
        val subject = LandmarkParser.clean(LandmarkParser.COLOURS.replace(subjectRaw, " ")).ifBlank { return Reading.Nothing }
        return when (intent) {
            "RIDE_CUE" -> Reading.Cue(subject, confidence)
            "REMEMBER" -> {
                val sense = runCatching { LandmarkKind.valueOf(o.optString("sense").uppercase()) }.getOrNull()
                    ?.takeIf { it != LandmarkKind.OTHER }
                val side = runCatching { Side.valueOf(o.optString("side").uppercase()) }.getOrDefault(Side.NONE)
                val habit = o.optString("habit").trim().lowercase().takeUnless { CLEAR.containsMatchIn(it) }.orEmpty()
                val meaning = o.optString("meaning").trim().lowercase().takeUnless { CLEAR.containsMatchIn(it) }.orEmpty()
                Reading.Note(
                    AnchorParser.Anchor(
                        subject = subject,
                        person = o.optString("person").trim(),
                        habit = habit,
                        meaning = meaning,
                        relatesTo = o.optString("relatesTo").trim().lowercase(),
                        sense = sense,
                        side = side,
                        words = words
                    ),
                    confidence
                )
            }
            else -> Reading.Nothing
        }
    }
}
