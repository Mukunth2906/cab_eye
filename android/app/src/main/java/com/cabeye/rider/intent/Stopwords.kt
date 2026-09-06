package com.cabeye.rider.intent

/**
 * The words that carry no destination.
 *
 * ## Why this is one shared list and not three private ones
 * Three separate pieces of the pipeline need to agree on what "nothing was said" means: the
 * classifier deciding whether an utterance is INCOMPLETE, the destination extractor deciding
 * what residue to hand the gazetteer, and the matching gates deciding whether a phrase has
 * enough substance to act on. If those three lists drift apart, the app can simultaneously
 * believe an utterance named a place (so it books) and that the same utterance was empty (so
 * it never asks). That disagreement is the machinery behind the worst bug in the product —
 * a confident booking from meaningless input — so the list lives in exactly one place.
 */
object Stopwords {

    /**
     * Function words, politeness, and disfluency.
     *
     * Filler is included deliberately: on-device recognition transcribes "um" and "uh" as real
     * words, and a rider who trails off mid-sentence often leaves one behind. Treating them as
     * substance would make `"take me to um"` look like a destination query for a place called
     * "um" — which is exactly the shape of input the app must refuse to act on.
     *
     * Nothing here appears in any place name in the gazetteer; that is checked by unit test,
     * because adding a place called "Race Course" the day someone adds "course" to this list
     * would silently make it unbookable.
     */
    val WORDS: Set<String> = setOf(
        // function words
        "to", "the", "a", "an", "of", "at", "in", "on", "for", "and",
        // first person / intent verbs left over after carrier stripping
        "i", "me", "my", "want", "need", "go", "going", "get", "take", "book", "please",
        // politeness and time
        "now", "just", "okay", "ok", "thanks", "thank", "you",
        // greetings, so "hey take me to Adyar" extracts "adyar" and not "hey adyar".
        // A greeting on its own is caught earlier by the classifier and answered properly;
        // here it is only ever debris in front of a real request.
        "hi", "hii", "hey", "hello", "helo", "hai", "vanakkam",
        // disfluency, as the recogniser actually returns it
        "um", "uh", "er", "erm", "hmm", "ah", "like", "so", "well"
    )

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
    private val WHITESPACE = Regex("\\s+")

    /** Lowercase, strip punctuation, collapse whitespace. The one normal form. */
    fun normalise(text: String): String =
        text.lowercase()
            .replace(NON_ALPHANUMERIC, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Removes stopwords from an already-[normalise]d string.
     *
     * @return what is left, which may be blank — and a blank result is a meaningful answer,
     *   not an error. It is the difference between "take me to Adyar" and "take me to".
     */
    fun strip(normalised: String): String =
        normalised.split(" ")
            .filter { it.isNotBlank() && it !in WORDS }
            .joinToString(" ")

    /** True when every word was a stopword — the input was polite, fluent, and empty. */
    fun isAllStopwords(normalised: String): Boolean =
        normalised.isNotBlank() && strip(normalised).isBlank()
}
