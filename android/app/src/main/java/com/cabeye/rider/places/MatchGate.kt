package com.cabeye.rider.places

import com.cabeye.rider.intent.Stopwords
import com.cabeye.rider.state.PlaceOption

/**
 * The bar a candidate must clear before the app is allowed to book a ride on it.
 *
 * ## The bug this replaces
 * Step 1 had exactly one number between "the recogniser produced some text" and "a real cab is
 * on its way to a real address": a `score > 0.25f` filter. That is not a decision procedure,
 * it is a formality. `"take me to Adyer"` scores 0.44 against Adyar — a single mistyped vowel,
 * genuinely ambiguous — and the app booked it silently, with no question asked, because 0.44
 * is greater than 0.25.
 *
 * For a rider who cannot see the screen, that is the worst class of failure in the product:
 * **acting confidently on weak evidence.** They get a real cab, to a real place, that they
 * never named, and the first indication anything went wrong is arriving somewhere else.
 *
 * ## The rule
 * Prefer asking over guessing. Silence is better than a wrong booking; a question is better
 * than both. Four independent gates, all of which must pass, and only then the ambiguity
 * budget:
 *
 * ```
 * gate 1  destination phrase >= 3 chars after stopword removal
 * gate 2  phrase is not entirely stopwords or filler
 * gate 3  best score >= 0.5
 * gate 4  match covers at least one WHOLE TOKEN of the place name
 *
 *   all pass          -> Proceed   -> then the ambiguity budget, unchanged
 *   0.30 <= s < 0.50  -> NearMiss  -> "Did you mean Adyar?"   yes / no, by voice
 *   otherwise         -> Reject    -> the recovery ladder
 * ```
 *
 * ## Why gate 4 exists separately from gate 3
 * Because score alone cannot tell the difference between evidence and coincidence. A two-letter
 * query scores well against a two-letter token for arithmetic reasons that have nothing to do
 * with what the rider meant — which is precisely how a trailing "to" can come to look like
 * "T Nagar". Requiring the overlap to align to a complete word of the place name makes that
 * whole family of accidents unrepresentable, no matter how the scorer is later tuned.
 *
 * ## Purity
 * No Android imports, no logging side effects. Every result carries its own [Decision.diagnostic]
 * string and the caller logs it — which is what lets all of this be proved by JVM unit test
 * rather than by holding a phone and hoping.
 */
object MatchGate {

    /** Gate 3. Below this the app may ask, but must never act. */
    const val CONFIDENCE_FLOOR = 0.5f

    /** Gate 1. Shorter than this is not a place name, it is debris. */
    const val MIN_PHRASE_LENGTH = 3

    /** Which gate turned a candidate away. Named so the log says *why*, not just "no". */
    enum class FailedGate {
        /** Gate 1 — fewer than [MIN_PHRASE_LENGTH] characters of substance. */
        TOO_SHORT,

        /** Gate 2 — every word was a stopword or filler: "to", "the", "please", "um". */
        ALL_STOPWORDS,

        /** No candidate scored even [Gazetteer.NEAR_MISS_FLOOR]. Nothing to ask about. */
        NO_CANDIDATE,

        /** Gate 4 — the best candidate matched a fragment of a word, not a whole one. */
        FRAGMENT_MATCH
    }

    /**
     * What the gates concluded.
     *
     * @property diagnostic one line naming the score, the gate that failed and the branch
     *   taken. The brief requires this on **every** utterance, including the successful ones —
     *   a gate that only logs its rejections cannot be shown to be calibrated.
     */
    sealed interface Decision {
        val diagnostic: String

        /**
         * Every gate passed. The candidate may be acted on, subject to the ambiguity budget.
         *
         * @param top the winning candidate
         * @param runnerUp second place, or null; the ambiguity budget needs both
         */
        data class Proceed(
            val top: PlaceOption,
            val runnerUp: PlaceOption?,
            override val diagnostic: String
        ) : Decision

        /**
         * Plausible but not good enough to act on. Ask a yes/no question out loud, and — per
         * the rule that a spoken question obliges an open microphone — listen for the answer.
         */
        data class NearMiss(
            val candidate: PlaceOption,
            override val diagnostic: String
        ) : Decision

        /** Not enough evidence to book or even to ask about. The recovery ladder takes over. */
        data class Reject(
            val gate: FailedGate,
            val bestScore: Float,
            override val diagnostic: String
        ) : Decision
    }

    /**
     * Applies the gates.
     *
     * @param phrase the destination fragment as the rider said it, carrier phrases already
     *   stripped but stopwords still present — gates 1 and 2 need to see them
     * @param candidates output of [Gazetteer.score], best first
     */
    fun evaluate(phrase: String, candidates: List<PlaceOption>): Decision {
        val normalised = Gazetteer.normalise(phrase)
        val substantive = Stopwords.strip(normalised)

        // ---- Gate 2 first, because it explains gate 1's failure better ------------------
        // "to" fails both gates. Reporting it as "entirely stopwords" tells the rider (and the
        // log) something true and actionable; reporting it as "too short" is technically also
        // true and completely useless.
        if (normalised.isNotBlank() && substantive.isBlank()) {
            return Decision.Reject(
                gate = FailedGate.ALL_STOPWORDS,
                bestScore = 0f,
                diagnostic = "GATE fail=ALL_STOPWORDS phrase=\"$normalised\" branch=REJECT"
            )
        }

        // ---- Gate 1 ---------------------------------------------------------------------
        if (substantive.length < MIN_PHRASE_LENGTH) {
            return Decision.Reject(
                gate = FailedGate.TOO_SHORT,
                bestScore = 0f,
                diagnostic = "GATE fail=TOO_SHORT phrase=\"$substantive\" " +
                        "len=${substantive.length} min=$MIN_PHRASE_LENGTH branch=REJECT"
            )
        }

        val top = candidates.firstOrNull()
            ?: return Decision.Reject(
                gate = FailedGate.NO_CANDIDATE,
                bestScore = 0f,
                diagnostic = "GATE fail=NO_CANDIDATE phrase=\"$substantive\" branch=REJECT"
            )

        // ---- Gate 4 ---------------------------------------------------------------------
        // Checked before the score branch, so a fragment match can never be promoted to a
        // near-miss question either. "Did you mean T Nagar?" after the rider said "to" is not
        // a helpful question; it is the same wrong guess wearing a question mark.
        if (!top.coversWholeToken) {
            return Decision.Reject(
                gate = FailedGate.FRAGMENT_MATCH,
                bestScore = top.score,
                diagnostic = "GATE fail=FRAGMENT_MATCH phrase=\"$substantive\" " +
                        "candidate=\"${top.name}\" score=${fmt(top.score)} branch=REJECT"
            )
        }

        // ---- Gate 3, and the branch on score --------------------------------------------
        return if (top.score >= CONFIDENCE_FLOOR) {
            Decision.Proceed(
                top = top,
                runnerUp = candidates.getOrNull(1),
                diagnostic = "GATE pass phrase=\"$substantive\" candidate=\"${top.name}\" " +
                        "score=${fmt(top.score)} floor=${fmt(CONFIDENCE_FLOOR)} branch=PROCEED"
            )
        } else {
            Decision.NearMiss(
                candidate = top,
                diagnostic = "GATE near_miss phrase=\"$substantive\" candidate=\"${top.name}\" " +
                        "score=${fmt(top.score)} band=[${fmt(Gazetteer.NEAR_MISS_FLOOR)}," +
                        "${fmt(CONFIDENCE_FLOOR)}) branch=ASK_YES_NO"
            )
        }
    }

    private fun fmt(value: Float): String = "%.3f".format(value)
}
