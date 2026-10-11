package com.cabeye.rider.vision

import com.cabeye.rider.memory.TextSimilarity
import java.util.Locale

/**
 * What the rider hears about a picture. Pure: no Android, so every rule is a JVM test.
 *
 * Rules:
 *  - Hazards first, always ("Careful: steps ahead.").
 *  - Positions as a clock face, the way orientation and mobility instructors teach it:
 *    12 o'clock straight ahead, 9 on the left, 3 on the right.
 *  - A few things, nearest first; in "keep telling me", only what is new since the last picture.
 *  - Never "the way is clear" or "safe": the camera cannot know that, and saying it would be the
 *    most dangerous sentence the app could produce.
 */
object SceneSpeech {

    /** At most this many things per sentence: a rider walking cannot hold more. */
    const val MAX_SPOKEN = 4

    private val HAZARDS = listOf(
        "step", "stair", "kerb", "curb", "pothole", "hole", "drain", "ditch", "manhole", "construction",
        "car", "auto", "rickshaw", "bus", "truck", "lorry", "van", "motorcycle", "motorbike", "scooter",
        "bike", "bicycle", "vehicle", "pole", "pillar", "post", "barrier", "cable", "wire", "puddle",
        "water", "wet", "dog", "cow", "animal", "glass", "door edge", "branch", "low ceiling", "fire"
    )

    private val CLEAR = Regex(
        "[^.]*\\b(path|way|road|route|area)\\b[^.]*\\b(is|looks|seems)\\s+(clear|safe|free)[^.]*\\.?|" +
            "[^.]*\\b(clear|safe) to (cross|walk|go|proceed)[^.]*\\.?|[^.]*\\bno obstacles?\\b[^.]*\\.?",
        RegexOption.IGNORE_CASE
    )

    /** What the rider might ask to find, and the labels that count as it. */
    val FIND_TARGETS: Map<String, List<String>> = linkedMapOf(
        "door" to listOf("door", "doorway", "entrance", "gate", "shutter"),
        "steps" to listOf("step", "steps", "stair", "stairs", "staircase", "kerb", "curb"),
        "seat" to listOf("chair", "bench", "seat", "couch", "sofa", "stool"),
        "person" to listOf("person", "people", "man", "woman", "boy", "girl", "child", "crowd"),
        "vehicle" to listOf("car", "auto", "rickshaw", "bus", "vehicle", "taxi", "motorcycle", "scooter", "van", "truck"),
        "bus stop" to listOf("bus stop", "bus shelter", "bus"),
        "table" to listOf("table", "desk", "counter"),
        "text" to listOf("text", "sign", "board", "label", "poster")
    )

    /** Clock position from the horizontal centre of a box (0 = left edge of the picture, 1 = right). */
    fun clock(centerX: Float): String = when {
        centerX < 0.2f -> "10 o'clock"
        centerX < 0.4f -> "11 o'clock"
        centerX <= 0.6f -> "12 o'clock"
        centerX <= 0.8f -> "1 o'clock"
        else -> "2 o'clock"
    }

    /** "on your left" / "ahead" / "on your right", for walk landmarks. */
    fun side(o: SceneObject): String {
        val x = o.box?.centerX ?: return "ahead"
        return when {
            x < 0.35f -> "on your left"
            x > 0.65f -> "on your right"
            else -> "ahead"
        }
    }

    fun isHazard(label: String): Boolean {
        val l = label.lowercase(Locale.ROOT)
        return HAZARDS.any { Regex("\\b${Regex.escape(it)}").containsMatchIn(l) }
    }

    /** Nearest first: a bigger box is (usually) closer; a known distance beats a guess. */
    private fun nearestFirst(objects: List<SceneObject>): List<SceneObject> =
        objects.sortedWith(compareBy<SceneObject> { it.distanceM ?: (10.0 / (it.box?.area?.coerceAtLeast(0.01f) ?: 0.05f)) })

    /** "a door at 12 o'clock, about 3 metres" */
    fun phrase(o: SceneObject): String {
        val name = aOrAn(o.label.lowercase(Locale.ROOT))
        val where = o.clock.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
        val far = o.distanceM?.let { d ->
            when {
                d < 1.0 -> ", very close"
                d < 10 -> ", about ${d.toInt().coerceAtLeast(1)} ${if (d.toInt() <= 1) "metre" else "metres"}"
                else -> ", about ${(d / 5).toInt() * 5} metres"
            }
        } ?: o.box?.let { if (it.height > 0.6f) ", close" else "" }.orEmpty()
        return "$name$where$far"
    }

    /** The full description of one picture. */
    fun describe(scene: Scene): String {
        val parts = mutableListOf<String>()
        val hazards = scene.hazards.ifEmpty {
            scene.objects.filter { isHazard(it.label) }.take(2).map { phrase(it) }
        }
        if (hazards.isNotEmpty()) parts += "Careful: ${hazards.joinToString(", ")}."

        if (scene.source == SceneSource.GEMINI && scene.summary.isNotBlank()) {
            parts += scene.summary.trim().let { if (it.endsWith(".")) it else "$it." }
        }
        val things = nearestFirst(scene.objects).take(MAX_SPOKEN)
        if (things.isNotEmpty()) {
            parts += (if (scene.source == SceneSource.GEMINI) "I can see " else "I think I see ") +
                listWords(things.map { phrase(it) }) + "."
        } else if (scene.summary.isNotBlank() && scene.source == SceneSource.ON_DEVICE) {
            parts += scene.summary.trim().let { if (it.endsWith(".")) it else "$it." }
        }
        if (scene.text.isNotBlank()) parts += "There's writing: ${scene.text.trim().take(200)}."
        if (parts.isEmpty()) return "I can't make out anything yet. Hold the phone at chest height and turn slowly."
        return noClearClaims(parts.joinToString(" "))
    }

    /**
     * For "keep telling me": only what wasn't there in the last few pictures. Hazards are said
     * again whenever they appear. Returns null when nothing is new.
     *
     * @param recent labels heard recently (lower case)
     */
    fun changes(scene: Scene, recent: Set<String>): String? {
        val fresh = nearestFirst(scene.objects).filter { it.label.lowercase(Locale.ROOT) !in recent }
        val hazards = fresh.filter { isHazard(it.label) }
        val others = fresh.filterNot { isHazard(it.label) }.take(2)
        if (hazards.isEmpty() && others.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (hazards.isNotEmpty()) parts += "Careful: ${listWords(hazards.take(2).map { phrase(it) })}."
        if (others.isNotEmpty()) parts += "${listWords(others.map { phrase(it) }).replaceFirstChar { it.uppercase() }}."
        return noClearClaims(parts.joinToString(" "))
    }

    /** The canonical find target for what the rider said ("find the door" → "door"). */
    fun targetOf(said: String): String {
        val t = said.lowercase(Locale.ROOT).trim()
        return FIND_TARGETS.entries.firstOrNull { (k, labels) -> t.contains(k) || labels.any { t.contains(it) } }?.key
            ?: t.removePrefix("the ").removePrefix("a ").trim()
    }

    /** "Find the door": is it in this picture, and where. */
    fun find(target: String, scene: Scene): String {
        if (scene.found.isNotBlank()) return noClearClaims(scene.found)
        val wanted = FIND_TARGETS[target] ?: listOf(target)
        val hit = nearestFirst(scene.objects).firstOrNull { o ->
            val l = o.label.lowercase(Locale.ROOT)
            wanted.any { w -> l.contains(w) || TextSimilarity.similarity(l, w) >= 0.85f }
        }
        if (hit != null) return "${phrase(hit).replaceFirstChar { it.uppercase() }}."
        if (target == "text" && scene.text.isNotBlank()) return "There's writing: ${scene.text.take(200)}."
        return "I can't see ${aOrAn(target)} yet. Turn slowly."
    }

    /** A landmark sentence for the walk route ("bench on your left"), or null for things not worth keeping. */
    fun landmarkWords(o: SceneObject): String? {
        val l = o.label.lowercase(Locale.ROOT)
        if (l in setOf("person", "people", "man", "woman", "boy", "girl", "child", "crowd", "car", "vehicle", "auto", "bus")) return null
        return "$l ${side(o)}"
    }

    fun noClearClaims(s: String): String = s.replace(CLEAR, "").replace(Regex("\\s{2,}"), " ").trim()

    private fun listWords(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    fun aOrAn(label: String): String {
        val l = label.trim()
        if (l.isEmpty()) return l
        val first = l.split(' ').first()
        if (first in setOf("the", "a", "an", "some") || (l.endsWith("s") && !l.endsWith("ss"))) return l
        return (if (first.first() in "aeiou") "an " else "a ") + l
    }
}
