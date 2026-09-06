package com.cabeye.rider.places

import com.cabeye.rider.state.PlaceOption
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A known place.
 *
 * @param aliases the forms people actually say. "Coimbatore Junction" is almost never spoken in
 *   full — riders say "junction" or "railway station" — and a gazetteer that only matches the
 *   official name would miss most real utterances.
 */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val aliases: List<String> = emptyList()
)

/**
 * The hardcoded gazetteer, scoped to one active [City].
 *
 * This remains the offline fallback for the live Google Places resolver. It is enough to
 * exercise the resolution path and confidence gate predictably when Google is unavailable.
 *
 * ## What this object does and does not decide
 * It scores. It does **not** decide whether a score is good enough to act on — that is
 * [MatchGate]'s job, and the separation is deliberate. Step 1 folded the two together behind a
 * single `> 0.25f` filter, which meant "this place is vaguely similar" and "book this rider a
 * cab here" were the same statement. They are not the same statement, and conflating them is
 * how the app came to book rides on evidence far too weak to justify one.
 */
object Gazetteer {

    /**
     * Floor below which a candidate is not worth returning at all.
     *
     * This is the **near-miss floor**, not the booking bar. Anything from here to
     * [MatchGate.CONFIDENCE_FLOOR] is a candidate the app may *ask about* ("Did you mean
     * Adyar?") but must never act on. Below it there is no meaningful evidence and the
     * recovery ladder takes over.
     */
    const val NEAR_MISS_FLOOR = 0.30f

    /**
     * Which city the gazetteer resolves against.
     *
     * A plain `var` with no Android dependency, so the whole scoring path stays testable on the
     * JVM. Persistence lives in `RiderPreferences`, which reads this at startup and writes it
     * when the rider says "switch to Chennai" — keeping the storage concern out of the code
     * that has to be provable.
     *
     * Defaults to Coimbatore because that is where this build is demonstrated.
     */
    var activeCity: City = City.COIMBATORE

    /** Places in the active city. */
    val places: List<Place> get() = activeCity.places

    /**
     * Scores every place in the **active city** against a spoken destination, best first.
     *
     * Scoring is deliberately simple and explainable — an opaque score would make the
     * clarification gate's numbers impossible to reason about during evaluation:
     *
     *  - exact alias match                     → 1.00
     *  - the query contains an alias in full   → 0.90, scaled by how much of the query it covers
     *  - all alias words appear in the query   → 0.75
     *  - some alias words appear               → proportional, up to 0.65
     *  - close-but-misspelled single word      → up to 0.55 via edit distance
     *
     * @param spokenDestination the destination fragment, already stripped of "take me to"
     */
    fun score(spokenDestination: String): List<PlaceOption> =
        scoreIn(activeCity, spokenDestination)

    /**
     * Scores against **every** city, used only after the active city has already failed.
     *
     * This exists so a failure can be diagnosed rather than merely reported. A rider in the
     * Coimbatore build who asks for Adyar has not mispronounced anything and will get nowhere
     * by trying again more clearly; they need to hear that the place is real, that it is in
     * Chennai, and that there is a command to switch. Running this only on the failure path
     * keeps it off the latency budget for every successful booking.
     *
     * @return the best cross-city candidate, or null if no city knows the place either
     */
    fun findInOtherCities(spokenDestination: String): Pair<City, PlaceOption>? =
        City.entries
            .filter { it != activeCity }
            .mapNotNull { city ->
                scoreIn(city, spokenDestination)
                    .firstOrNull { it.score >= MatchGate.CONFIDENCE_FLOOR && it.coversWholeToken }
                    ?.let { city to it }
            }
            .maxByOrNull { it.second.score }

    private fun scoreIn(city: City, spokenDestination: String): List<PlaceOption> {
        val query = normalise(spokenDestination)
        if (query.isBlank()) return emptyList()

        val queryWords = query.split(" ").filter { it.isNotBlank() }

        return city.places.map { place ->
            val forms = (place.aliases + place.name).map { normalise(it) }.distinct()

            // Take the best-scoring alias, and carry that alias's own token-coverage verdict
            // with it. Deciding coverage separately from the score would let a high score from
            // one alias pair up with a coverage flag earned by a different one.
            val best = forms
                .map { form -> scoreForm(query, queryWords, form) }
                .maxByOrNull { it.score }
                ?: FormMatch(0f, false)

            PlaceOption(
                name = place.name,
                latitude = place.latitude,
                longitude = place.longitude,
                score = best.score,
                coversWholeToken = best.coversWholeToken,
                cityName = city.displayName
            )
        }
            .filter { it.score >= NEAR_MISS_FLOOR }
            .sortedByDescending { it.score }
    }

    /**
     * How well one alias matched, and whether that match was anchored to a whole word.
     *
     * @param coversWholeToken true when the overlap between query and alias was at least one
     *   complete word of the alias. A fragment match — "besan" inside "besant nagar", or the
     *   single letter "t" inside "t nagar" — is false, however well it scores.
     */
    private data class FormMatch(val score: Float, val coversWholeToken: Boolean)

    private fun scoreForm(query: String, queryWords: List<String>, form: String): FormMatch {
        if (query == form) return FormMatch(1.0f, true)

        val formWords = form.split(" ").filter { it.isNotBlank() }

        if (query.contains(form)) {
            // Reward the alias covering more of what was said: "airport" inside "airport"
            // should beat "airport" inside "somewhere near the airport road junction".
            // The entire alias appears in the query, so every one of its words is covered.
            val coverage = form.length.toFloat() / query.length.toFloat()
            return FormMatch(0.90f * (0.7f + 0.3f * coverage), true)
        }

        // The rider said a *shorter* form than the full name — "anna" for "Anna Nagar East",
        // "puram" for "RS Puram". Scored below a full match but high enough to surface, and
        // scaled by how much of the name was actually said, so a vaguer utterance scores lower
        // and is therefore more likely to tie with a rival and trip the gate.
        //
        // The 3-character floor stops one- and two-letter fragments matching half the
        // gazetteer, which would make every query look ambiguous.
        if (query.length >= 3 && form.contains(query)) {
            val coverage = query.length.toFloat() / form.length.toFloat()

            // Substring containment is NOT enough to claim whole-token coverage. "puram" is a
            // whole word of "rs puram" but only a tail fragment of "gandhipuram", and the
            // rider's evidence for the two is not the same strength even though the substring
            // test cannot tell them apart.
            val whole = formWords.any { it == query } || queryWords.any { qw -> formWords.contains(qw) }
            return FormMatch(0.80f * (0.5f + 0.5f * coverage), whole)
        }

        val matched = formWords.count { fw -> queryWords.any { qw -> qw == fw } }

        // Whole words of the alias were spoken verbatim — that is whole-token coverage by
        // definition, whether all of them matched or only some.
        if (matched == formWords.size && formWords.isNotEmpty()) return FormMatch(0.75f, true)
        if (matched > 0) return FormMatch(0.65f * (matched.toFloat() / formWords.size), true)

        // Nothing matched outright — allow for mis-recognition of a single word. This compares
        // whole word against whole word, so a hit here is anchored even though it is fuzzy;
        // what it lacks is *score*, and the near-miss branch is what handles that.
        val fuzzy = queryWords.maxOfOrNull { qw ->
            formWords.maxOfOrNull { fw -> similarity(qw, fw) } ?: 0f
        } ?: 0f

        return if (fuzzy > 0.7f) FormMatch(0.55f * fuzzy, true) else FormMatch(0f, false)
    }

    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalise(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Normalised Levenshtein similarity, 0..1. */
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return 1f - (distance.toFloat() / longest.toFloat())
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Great-circle distance in kilometres.
     *
     * This is what feeds DIVERGE in the clarification gate: two tied candidates only justify
     * interrupting the rider if being wrong would actually send them somewhere else.
     */
    fun distanceKm(a: PlaceOption, b: PlaceOption): Float {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return (2 * earthRadiusKm * asin(sqrt(h))).toFloat()
    }
}
