package com.cabeye.rider.intent

import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.ThemeChoice

/**
 * What the rider meant.
 */
sealed interface RiderIntent {

    /**
     * Book a ride.
     *
     * @param destinationQuery the destination fragment with carrier phrases, vehicle words and
     *   stopwords removed — this is what gets handed to the gazetteer to score
     * @param rawDestination the same fragment with carrier and vehicle words removed but
     *   **stopwords intact**. The matching gates need to see the stopwords: gates 1 and 2 exist
     *   precisely to catch phrases that are nothing but function words, and they cannot do that
     *   job if the only phrase they are shown has already had those words deleted.
     * @param rideType vehicle class, defaulting to auto when unstated
     * @param rideTypeWasExplicit whether the rider actually said "auto"/"cab"/"bike", as
     *   opposed to the default being applied. This is what stops the app re-asking for a slot
     *   it already has when it has to ask for the destination.
     * @param confidence how sure the parser is of the *intent* (not of the place; the
     *   gazetteer scores that separately)
     */
    data class Book(
        val destinationQuery: String,
        val rawDestination: String,
        val rideType: RideType,
        val rideTypeWasExplicit: Boolean,
        val confidence: Float
    ) : RiderIntent

    /** Answer to a clarification question — the rider named one of the options. */
    data class ChooseOption(val spokenChoice: String) : RiderIntent

    /** Affirmative, for the near-miss question "Did you mean Adyar?". */
    data object Yes : RiderIntent

    /** Negative, same question. */
    data object No : RiderIntent

    // --- Global voice commands ---------------------------------------------------------
    data object Cancel : RiderIntent
    data object Status : RiderIntent
    data object Repeat : RiderIntent
    data object CallDriver : RiderIntent
    data object BookAgain : RiderIntent
    data object Help : RiderIntent
    data object Greeting : RiderIntent

    /** Speak the gazetteer, in interruptible groups. The way out of a repeated failure. */
    data object ListPlaces : RiderIntent

    /** Escape hatch offered at the bottom of the recovery ladder. */
    data object CallSupport : RiderIntent

    /**
     * Change the active city.
     *
     * @param spokenCity what the rider said after "switch to". Resolved against [com.cabeye.rider.places.City]
     *   by the caller rather than here, so this package stays independent of the gazetteer and
     *   an unrecognised city can be answered with the list of ones that do exist.
     */
    data class SwitchCity(val spokenCity: String) : RiderIntent

    /** Change the visual theme by voice — the screen is for low vision, so it must be spoken to. */
    data class SetTheme(val theme: ThemeChoice) : RiderIntent

    /** Debug-only: walk the full ride arc so all eleven states can be seen without a backend. */
    data object DemoRide : RiderIntent

    /** Nothing matched. Triggers the LLM escalation once that exists. */
    data object Unknown : RiderIntent
}

/**
 * Escalation target for utterances the regex path cannot handle.
 *
 * **Deliberately not implemented.** The brief gates the LLM behind explicit confirmation,
 * and the regex path below handles the booking grammar and every global command on its own.
 * When this is built it should return structured JSON matching [RiderIntent], and
 * [Classifier] should call it only on `UtteranceClass.UNKNOWN` — never on the fast path.
 */
interface LlmIntentResolver {
    suspend fun resolve(transcript: String): RiderIntent
}

/**
 * Regex fast path.
 *
 * ## Why regex first
 * An LLM call costs a network round trip, and the budget for intent parsing is 300 ms
 * end-to-end. The overwhelming majority of real utterances are a carrier phrase plus a
 * place name — "take me to Peelamedu", "auto to the airport" — which a regex handles in
 * microseconds. The LLM is for the tail, not the trunk, and every parse records which path
 * was taken so the split can actually be measured.
 *
 * ## What moved out of here in step 2
 * This object no longer decides *whether an utterance is a booking at all*. That is
 * [Classifier]'s job now, and the split is the fix for two of the three reported bugs: this
 * parser assumed every utterance was a booking, so "hi" became a query for a place called
 * "hi", and "take me to" became indistinguishable from gibberish rather than from an
 * unfinished sentence.
 */
object IntentParser {

    /**
     * Carrier phrases stripped before the remainder is treated as a destination.
     *
     * Ordered longest-first so "i want to go to" is consumed before "go to" can match part
     * of it and leave debris behind.
     */
    val CARRIER_PHRASES = listOf(
        "i want to go to", "i need to go to", "i would like to go to",
        "can you take me to", "please take me to", "i want to book",
        "take me to", "drop me at", "drop me to", "go to", "take me",
        "book a ride to", "book a ride", "book me", "book an", "book a",
        "i want an", "i want a", "i need an", "i need a",
        "get me to", "get me an", "get me a", "ride to", "to go to",
        // Tamil-English code switching, which is how this is actually said in Coimbatore.
        // "-ku poganum" = "want to go to", and it comes AFTER the place name rather than
        // before it, so it is stripped as a suffix by the same mechanism.
        "ku poganum", "ku polam", "poganum", "polam"
    ).sortedByDescending { it.length }

    val CANCEL = Regex("\\b(cancel|stop|abort|never mind|nevermind|forget it)\\b", RegexOption.IGNORE_CASE)
    val STATUS = Regex("\\b(status|where is (my )?(driver|cab|auto|ride)|how (long|far)|eta)\\b", RegexOption.IGNORE_CASE)
    val REPEAT = Regex("\\b(repeat|say (that )?again|what did you say|pardon)\\b", RegexOption.IGNORE_CASE)
    val CALL_DRIVER = Regex("\\b(call (the )?driver|phone (the )?driver|ring (the )?driver)\\b", RegexOption.IGNORE_CASE)
    val BOOK_AGAIN = Regex("\\b(book again|same again|repeat (my )?(last )?(ride|trip)|book another)\\b", RegexOption.IGNORE_CASE)
    val HELP = Regex("\\b(help|what can i say|commands|options)\\b", RegexOption.IGNORE_CASE)

    private val AUTO = Regex("\\b(auto|autorickshaw|auto rickshaw|rickshaw|tuk tuk)\\b", RegexOption.IGNORE_CASE)
    private val CAB = Regex("\\b(cab|car|taxi|sedan)\\b", RegexOption.IGNORE_CASE)
    private val BIKE = Regex("\\b(bike|two wheeler|scooter|motorcycle)\\b", RegexOption.IGNORE_CASE)

    /**
     * What a booking-shaped utterance turned out to contain.
     *
     * The point of this type is that it distinguishes **"the rider named nowhere"** from
     * **"the rider said nothing booking-shaped at all"**. Step 1 returned a bare `String` from
     * the extractor, so both cases arrived as `""` and the app could only treat them the same
     * way — which is why "take me to" dead-ended with a generic apology instead of simply
     * asking where to.
     *
     * @param destination stopword-stripped residue, ready for the gazetteer
     * @param rawDestination residue with stopwords intact, for the matching gates
     * @param hadCarrier a booking pattern was recognised — the rider was trying to travel
     * @param rideType the vehicle class, defaulted when unstated
     * @param rideTypeWasExplicit whether [rideType] was actually spoken
     */
    data class Extraction(
        val destination: String,
        val rawDestination: String,
        val hadCarrier: Boolean,
        val rideType: RideType,
        val rideTypeWasExplicit: Boolean
    )

    /**
     * Pulls the destination and vehicle class out of a booking-shaped utterance.
     *
     * Vehicle words are removed *after* the carrier phrases so "book a cab to Adyar" reduces
     * cleanly to "adyar" rather than leaving "a to adyar" behind.
     */
    fun extract(text: String): Extraction {
        val lower = text.lowercase()

        val bike = BIKE.containsMatchIn(lower)
        val cab = CAB.containsMatchIn(lower)
        val auto = AUTO.containsMatchIn(lower)

        val rideType = when {
            bike -> RideType.BIKE
            cab -> RideType.CAB
            auto -> RideType.AUTO
            // Auto is the sensible default here, and stating a default beats asking a question
            // the rider almost always answers the same way.
            else -> RideType.AUTO
        }

        var working = lower
        var hadCarrier = false

        for (phrase in CARRIER_PHRASES) {
            if (working.contains(phrase)) {
                hadCarrier = true
                working = working.replace(phrase, " ")
            }
        }

        working = AUTO.replace(working, " ")
        working = CAB.replace(working, " ")
        working = BIKE.replace(working, " ")

        val raw = Stopwords.normalise(working)

        return Extraction(
            destination = Stopwords.strip(raw),
            rawDestination = raw,
            hadCarrier = hadCarrier,
            rideType = rideType,
            rideTypeWasExplicit = bike || cab || auto
        )
    }
}
