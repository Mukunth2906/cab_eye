package com.cabeye.rider.walk

import kotlin.math.roundToInt

/**
 * A stored route → what is said, at the detail the rider needs today.
 *
 * Written the way an O&M specialist dictates a route: one stretch at a time, distance in
 * metres and the rider's own steps, the landmark that marks each turn, the side things are on.
 * Three levels (NavCog's finding: the right amount to say depends on the person and how well
 * they know the route):
 *
 *  - FULL      — every stretch, distance and steps, every landmark with its side
 *  - BRIEF     — turns and the landmark that marks each, rounded distances
 *  - LANDMARKS — one line of cues: "left at the kerb, right after the bakery smell, then the door"
 *
 * Safety, always: a landmark not confirmed for a while is said as "was there before — check for
 * it", and the narration never claims the way is clear. That one sentence is said as what it
 * is: the app cannot know.
 */
object WalkNarrator {

    const val STALE_DAYS = 30.0
    const val LOW_CONFIDENCE = 0.5
    const val SAFETY_NOTE = "Use your cane or dog as usual. I can't tell whether the way is clear."

    // ---- familiarity: how much to say ------------------------------------------------

    /**
     * FULL for the first two walks, BRIEF from the third, LANDMARKS from the sixth — held back
     * a level while the rider has recently asked for repeats or more detail, and shifted by
     * their own "more detail" / "less detail".
     */
    fun levelFor(route: WalkRoute, profile: WalkProfile): DetailLevel {
        val byWalks = when {
            route.walks < 2 -> 0
            route.walks < 5 -> 1
            else -> 2
        }
        val help = minOf(route.helpRequests, 2)
        val idx = (byWalks - help - profile.detailBias).coerceIn(0, 2)
        return DetailLevel.entries[idx]
    }

    // ---- the whole route ---------------------------------------------------------------

    fun narrate(route: WalkRoute, level: DetailLevel, now: Long): String = buildString {
        val segs = route.segments
        if (segs.isEmpty()) return "I don't have any steps saved for ${route.name}."
        when (level) {
            DetailLevel.FULL -> {
                append(intro(route))
                segs.indices.forEach { i -> append(' ').append(segment(route, i, DetailLevel.FULL, now)) }
                append(' ').append(SAFETY_NOTE)
            }
            DetailLevel.BRIEF -> {
                append(intro(route)).append(' ')
                append(segs.mapIndexed { i, s -> briefPart(s, i == segs.lastIndex) }.joinToString("; "))
                append('.')
                staleNote(route, now)?.let { append(' ').append(it) }
            }
            DetailLevel.LANDMARKS -> {
                append(cueLine(route).replaceFirstChar { it.uppercase() }).append('.')
                staleNote(route, now)?.let { append(' ').append(it) }
            }
        }
    }.replace(Regex("\\s+"), " ").trim()

    /** One stretch, for step-by-step guidance ("say next at the turn"). */
    fun segment(route: WalkRoute, index: Int, level: DetailLevel, now: Long): String {
        val s = route.segments[index]
        val last = index == route.segments.lastIndex
        val parts = mutableListOf<String>()
        val lead = if (route.segments.size > 1) "Part ${index + 1}. " else ""
        parts += lead + "Walk straight " + distance(s, withSteps = level == DetailLevel.FULL) + "."

        val passing = s.landmarks.filter { !it.isTurnCue || s.turn == TurnDirection.NONE }
            .filter { !(last && it == s.landmarks.lastOrNull() && it.kind in ENDINGS) }
        if (level != DetailLevel.LANDMARKS) {
            for (l in passing) {
                val where = if (level == DetailLevel.FULL && l.atMetres >= 3) " after about ${roundMetres(l.atMetres)} metres" else ""
                parts += "You'll pass ${phrase(l)}$where."
            }
        }
        if (s.turn != TurnDirection.NONE) {
            val cue = s.landmarks.lastOrNull { it.isTurnCue }
            parts += "Then ${s.turn.spoken}" + (cue?.let { " ${at(it)} ${phrase(it, withSide = false)}" } ?: "") + "."
        }
        if (last) {
            val end = s.landmarks.lastOrNull { it.kind in ENDINGS }
            parts += if (end != null) "You'll reach ${phrase(end)}. That's the end of the route."
            else "That's the end of the route."
        }
        if (level == DetailLevel.FULL) {
            s.landmarks.mapNotNull { caution(it, now) }.forEach { parts += it }
        }
        return parts.joinToString(" ")
    }

    // ---- pieces --------------------------------------------------------------------------

    private val ENDINGS = setOf(LandmarkKind.DOOR, LandmarkKind.GATE, LandmarkKind.STEPS, LandmarkKind.RAMP)

    private fun intro(route: WalkRoute): String {
        val from = route.start.ifBlank { if (route.placeName.isNotBlank()) "where the cab stops" else "the start" }
        val total = roundMetres(route.totalMetres)
        val turns = route.segments.count { it.turn != TurnDirection.NONE }
        val turnText = when (turns) { 0 -> "no turns"; 1 -> "one turn"; else -> "$turns turns" }
        return "${route.name.replaceFirstChar { it.uppercase() }}, from $from: about $total metres, $turnText."
    }

    private fun briefPart(s: Segment, last: Boolean): String {
        val d = "about ${roundMetres(s.metres)} metres"
        val cue = s.landmarks.lastOrNull { it.isTurnCue }
        return when {
            s.turn != TurnDirection.NONE ->
                "$d, then ${s.turn.spoken.removePrefix("turn ")}" + (cue?.let { " ${at(it)} ${phrase(it, withSide = false)}" } ?: "")
            last -> {
                val end = s.landmarks.lastOrNull { it.kind in ENDINGS }
                if (end != null) "$d to ${phrase(end)}" else d
            }
            else -> d
        }
    }

    /** "left at the kerb, right after the bakery smell, then the glass door" */
    fun cueLine(route: WalkRoute): String {
        val parts = mutableListOf<String>()
        route.segments.forEachIndexed { i, s ->
            if (s.turn != TurnDirection.NONE) {
                val cue = s.landmarks.lastOrNull { it.isTurnCue }
                parts += s.turn.spoken.removePrefix("turn ") + (cue?.let { " ${at(it)} ${phrase(it, withSide = false)}" } ?: " after about ${roundMetres(s.metres)} metres")
            }
            if (i == route.segments.lastIndex) {
                val end = s.landmarks.lastOrNull { it.kind in ENDINGS }
                parts += if (end != null) "then ${phrase(end, withSide = false)}" else "then about ${roundMetres(s.metres)} metres to the end"
            }
        }
        return parts.joinToString(", ")
    }

    /** "at the kerb" for things you reach, "after the bakery smell" for things you pass. */
    private fun at(l: Landmark): String = when (l.kind) {
        LandmarkKind.SMELL, LandmarkKind.SOUND, LandmarkKind.TEXTURE, LandmarkKind.TACTILE -> "after"
        else -> "at"
    }

    fun phrase(l: Landmark, withSide: Boolean = true): String {
        val article = if (l.text.firstOrNull()?.isDigit() == true ||
            l.text.split(' ').first() in NUMBER_WORDS) "" else "the "
        val side = if (withSide && l.side != Side.NONE) " ${l.side.spoken}" else ""
        return "$article${l.text}$side"
    }

    private val NUMBER_WORDS = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "a", "few", "some")

    /** Said for a landmark the stored route can't vouch for any more. */
    fun caution(l: Landmark, now: Long): String? {
        val age = l.ageDays(now)
        return when {
            l.confidence <= LOW_CONFIDENCE ->
                "${phrase(l, withSide = false).replaceFirstChar { it.uppercase() }} wasn't there last time, so don't rely on it."
            age >= STALE_DAYS ->
                "${phrase(l, withSide = false).replaceFirstChar { it.uppercase() }} was last confirmed ${ago(age)}, so check for it."
            else -> null
        }
    }

    private fun staleNote(route: WalkRoute, now: Long): String? =
        route.landmarks.mapNotNull { caution(it, now) }.firstOrNull()

    fun distance(s: Segment, withSteps: Boolean): String {
        val m = "about ${roundMetres(s.metres)} metres"
        val steps = s.steps
        return if (withSteps && steps != null && steps >= 5) "$m, about ${roundSteps(steps)} of your steps" else m
    }

    fun roundMetres(m: Double): Int = when {
        m < 10 -> maxOf(1, m.roundToInt())
        m < 50 -> (m / 5).roundToInt() * 5
        else -> (m / 10).roundToInt() * 10
    }

    private fun roundSteps(n: Int): Int = if (n < 20) n else (n / 5.0).roundToInt() * 5

    private fun ago(days: Double): String = when {
        days < 14 -> "${days.roundToInt()} days ago"
        days < 60 -> "${(days / 7).roundToInt()} weeks ago"
        else -> "${(days / 30).roundToInt()} months ago"
    }
}
