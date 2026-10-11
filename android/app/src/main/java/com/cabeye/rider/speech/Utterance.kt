package com.cabeye.rider.speech

/**
 * One answer, assembled from however many recogniser segments it took to say it.
 *
 * Android's recogniser stops at the first pause, so "nine eight seven six five … four three two
 * one zero" arrives as two segments, each with its own handful of guesses. This joins them back
 * into whole-answer guesses, best first, so the listener can still pick the one that fits.
 *
 * No Android dependencies, so the joining is covered by a JVM test.
 */
class Utterance {

    private val segments = mutableListOf<List<String>>()

    /** The latest streaming partial of the segment still in progress, if any. */
    var pendingPartial: String = ""
        private set

    val isEmpty: Boolean get() = segments.isEmpty() && pendingPartial.isBlank()

    /** Number of finished segments. */
    val segmentCount: Int get() = segments.size

    fun clear() {
        segments.clear()
        pendingPartial = ""
    }

    fun onPartial(text: String) {
        pendingPartial = text.trim()
    }

    /** A segment finished with these guesses, best first. Empty guesses are ignored. */
    fun addSegment(alternatives: List<String>) {
        val clean = alternatives.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        pendingPartial = ""
        if (clean.isNotEmpty()) segments += clean
    }

    /**
     * Keeps the in-progress partial as a segment of its own.
     *
     * Used when the session ends without the recogniser finalising the last segment — common
     * when a hold is released mid-word: the partial showed the words, then the recogniser
     * reported "no match". Throwing those words away is how "it doesn't catch anything" happens.
     */
    fun commitPartial() {
        val partial = pendingPartial
        pendingPartial = ""
        if (partial.isNotBlank()) segments += listOf(partial)
    }

    /** What has been heard so far, best guesses joined, including the partial in progress. */
    fun bestText(): String =
        (segments.map { it.first() } + listOfNotNull(pendingPartial.takeIf { it.isNotBlank() }))
            .joinToString(" ")

    /**
     * Whole-answer guesses, best first: every combination of each segment's top guesses, ordered
     * by how far down the lists they reach. Capped so a long answer cannot explode.
     */
    fun alternatives(perSegment: Int = 3, max: Int = 8): List<String> {
        if (segments.isEmpty()) return emptyList()
        var combos = listOf("" to 0)
        for (segment in segments) {
            val next = mutableListOf<Pair<String, Int>>()
            for ((text, rank) in combos) {
                segment.take(perSegment).forEachIndexed { i, alt ->
                    next += (if (text.isEmpty()) alt else "$text $alt") to (rank + i)
                }
            }
            combos = next.sortedBy { it.second }.take(max * 2)
        }
        return combos.sortedBy { it.second }.map { it.first }.distinct().take(max)
    }

    /** True when any joined guess satisfies [isComplete]. */
    fun anyComplete(isComplete: (String) -> Boolean): Boolean =
        alternatives().any(isComplete)
}
