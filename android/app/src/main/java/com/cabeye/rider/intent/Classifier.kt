package com.cabeye.rider.intent

import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.ThemeChoice

/**
 * The five things an utterance can be.
 *
 * Every utterance is sorted into exactly one of these **before** any place lookup happens.
 * That ordering is the whole point: step 1 had no classification step at all, so the app
 * assumed every noise the rider made was a booking, and "hi" became a search for a place
 * called "hi" that failed and dead-ended.
 */
enum class UtteranceClass {
    /** "hi", "hello", "good morning" — acknowledge, then invite the real request. */
    GREETING,

    /** "status", "cancel", "repeat", "help", "list places" — run it. */
    COMMAND,

    /**
     * A booking sentence that stopped short of naming anywhere: "take me to", "I want to go".
     *
     * The most important bucket, and the one that did not exist before. It is emphatically
     * **not** UNKNOWN — the rider was perfectly clear about wanting to travel — and it must
     * never become a booking, because there is nothing to book. It is a filled form with one
     * empty field, and the correct response is to ask for that one field.
     */
    INCOMPLETE,

    /** A booking sentence that named somewhere. Resolve it. */
    BOOKING,

    /** Anything else. Enter the recovery ladder. */
    UNKNOWN
}

/** A piece of information a booking needs. Only one can currently be missing. */
enum class Slot {
    DESTINATION
}

/**
 * The verdict on one utterance.
 *
 * @param kind which bucket it fell into
 * @param intent the command or booking to run, for COMMAND and BOOKING
 * @param missingSlot for INCOMPLETE, the one thing still needed
 * @param knownRideType a slot the rider **already filled**, carried forward so the follow-up
 *   question does not ask for it again. "Book an auto to" must be answered with "Where would
 *   you like to go?" and not with a fresh start that throws away the word "auto".
 * @param phrase the substantive residue of what was said, so a failure can name it back to
 *   the rider instead of apologising generically
 * @param diagnostic one line for the log
 */
data class Classification(
    val kind: UtteranceClass,
    val intent: RiderIntent = RiderIntent.Unknown,
    val missingSlot: Slot? = null,
    val knownRideType: RideType? = null,
    val phrase: String = "",
    val diagnostic: String = ""
)

/**
 * Sorts an utterance before anything tries to resolve it.
 *
 * ## Why order is the design
 * Every rule here is a `containsMatchIn` over the same string, so the only thing distinguishing
 * a good classifier from a dangerous one is which question gets asked first. Two orderings are
 * load-bearing:
 *
 *  - **Cancel is checked before everything.** It is the safety valve on optimistic booking, and
 *    a rider saying "cancel" must never have it read as a destination.
 *  - **Greeting is checked before booking, but only matches a bare greeting.** "hey take me to
 *    Peelamedu" is a booking with a friendly opener, not a greeting, and answering it with
 *    "Hello. Just say where you want to go." would be maddening.
 */
object Classifier {

    private val GREETING = Regex(
        "\\b(hi|hii|hey|hello|helo|hai|vanakkam|good morning|good afternoon|good evening)\\b",
        RegexOption.IGNORE_CASE
    )

    private val LIST_PLACES = Regex(
        "\\b(list places|list the places|what places|which places|places do you know|" +
                "where can you go|list of places)\\b",
        RegexOption.IGNORE_CASE
    )

    private val SWITCH_CITY = Regex(
        "\\b(?:switch|change|set)\\s+(?:the\\s+)?(?:city\\s+)?to\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val THEME = Regex(
        "\\b(yellow|light|bright|dark|black|high contrast)\\b.*\\btheme\\b|" +
                "\\btheme\\b.*\\b(yellow|light|bright|dark|black|high contrast)\\b",
        RegexOption.IGNORE_CASE
    )

    private val CALL_SUPPORT = Regex(
        "\\bcall\\s+(?:the\\s+|an?\\s+)?" +
                "(support|help ?line|customer (care|service)|agent|person|human|someone)\\b",
        RegexOption.IGNORE_CASE
    )

    private val DEMO_RIDE = Regex("\\b(demo ride|demo mode|simulate (a )?ride)\\b", RegexOption.IGNORE_CASE)

    private val YES = Regex(
        "^\\s*(yes|yeah|yep|yup|correct|right|that's right|thats right|sure|please do|go ahead)\\b",
        RegexOption.IGNORE_CASE
    )

    private val NO = Regex(
        "^\\s*(no|nope|nah|wrong|not that|no thanks|incorrect)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param transcript the final recognised utterance
     * @param rideActive whether a ride is in progress — gates the commands that only make
     *   sense mid-ride, so "status" said at idle is not mistaken for one
     */
    fun classify(transcript: String, rideActive: Boolean): Classification {
        val text = transcript.trim()

        if (text.isBlank()) {
            return Classification(
                kind = UtteranceClass.UNKNOWN,
                diagnostic = "CLASSIFY kind=UNKNOWN reason=blank"
            )
        }

        // ---- Commands ------------------------------------------------------------------
        // Cancel first and unconditionally. It is the safety valve on optimistic booking and
        // the one promise the design cannot break.
        if (IntentParser.CANCEL.containsMatchIn(text)) return command(RiderIntent.Cancel, text, "cancel")

        if (LIST_PLACES.containsMatchIn(text)) return command(RiderIntent.ListPlaces, text, "list_places")

        // Before HELP, because "call the helpline" contains "help".
        if (CALL_SUPPORT.containsMatchIn(text)) return command(RiderIntent.CallSupport, text, "call_support")

        // Theme before city, and the order is load-bearing: "switch to the light theme" is
        // matched by BOTH patterns, and the city pattern would capture "the light theme" as a
        // city name. The more specific rule has to be asked first.
        if (THEME.containsMatchIn(text)) {
            themeFrom(text)?.let { return command(RiderIntent.SetTheme(it), text, "set_theme") }
        }

        SWITCH_CITY.find(text)?.let { match ->
            val spoken = match.groupValues[1].trim()
            if (spoken.isNotBlank()) {
                return command(RiderIntent.SwitchCity(spoken), text, "switch_city")
            }
        }

        if (DEMO_RIDE.containsMatchIn(text)) return command(RiderIntent.DemoRide, text, "demo_ride")

        if (IntentParser.HELP.containsMatchIn(text)) return command(RiderIntent.Help, text, "help")
        if (IntentParser.REPEAT.containsMatchIn(text)) return command(RiderIntent.Repeat, text, "repeat")
        if (IntentParser.BOOK_AGAIN.containsMatchIn(text)) return command(RiderIntent.BookAgain, text, "book_again")

        if (rideActive) {
            if (IntentParser.CALL_DRIVER.containsMatchIn(text)) return command(RiderIntent.CallDriver, text, "call_driver")
            if (IntentParser.STATUS.containsMatchIn(text)) return command(RiderIntent.Status, text, "status")
        }

        // Anchored at the start of the utterance, so a place name that happens to contain
        // "no" cannot be read as a refusal.
        if (YES.containsMatchIn(text)) return command(RiderIntent.Yes, text, "yes")
        if (NO.containsMatchIn(text)) return command(RiderIntent.No, text, "no")

        // ---- Booking shape -------------------------------------------------------------
        val extraction = IntentParser.extract(text)

        // ---- Greeting ------------------------------------------------------------------
        // Only a *bare* greeting counts. If anything substantive survives once the greeting is
        // removed, the rider said something and is owed a real answer to it.
        if (GREETING.containsMatchIn(text)) {
            val withoutGreeting = Stopwords.strip(Stopwords.normalise(GREETING.replace(text, " ")))
            if (withoutGreeting.isBlank() && !extraction.hadCarrier) {
                return Classification(
                    kind = UtteranceClass.GREETING,
                    intent = RiderIntent.Greeting,
                    phrase = text,
                    diagnostic = "CLASSIFY kind=GREETING utterance=\"$text\""
                )
            }
        }

        // ---- Incomplete ----------------------------------------------------------------
        // Nothing of substance was said, in one of two shapes:
        //
        //  1. A booking pattern matched but no destination survived — "take me to", "book a cab".
        //  2. The whole utterance was function words — a bare "to", "the", "um".
        //
        // Shape 2 matters as much as shape 1 and is easy to miss. A dangling preposition is the
        // *signature* of a rider trailing off or the microphone cutting them short, and it is
        // the input that produced the worst bug in the product: "to" fuzzy-matched "T Nagar" on
        // a single letter and a real cab was booked to a real place the rider never named.
        // Classifying it here means it can never reach the scorer at all.
        //
        // Neither shape may ever become a booking. There is literally nothing to book.
        val nothingSubstantive = extraction.destination.isBlank() &&
                (extraction.hadCarrier || Stopwords.isAllStopwords(extraction.rawDestination))

        if (nothingSubstantive) {
            return Classification(
                kind = UtteranceClass.INCOMPLETE,
                missingSlot = Slot.DESTINATION,
                knownRideType = extraction.rideType.takeIf { extraction.rideTypeWasExplicit },
                phrase = text,
                diagnostic = "CLASSIFY kind=INCOMPLETE missing=DESTINATION " +
                        "carrier=${extraction.hadCarrier} " +
                        "knownRideType=${if (extraction.rideTypeWasExplicit) extraction.rideType.name else "-"}"
            )
        }

        // ---- Booking -------------------------------------------------------------------
        if (extraction.destination.isNotBlank()) {
            return Classification(
                kind = UtteranceClass.BOOKING,
                intent = RiderIntent.Book(
                    destinationQuery = extraction.destination,
                    rawDestination = extraction.rawDestination,
                    rideType = extraction.rideType,
                    rideTypeWasExplicit = extraction.rideTypeWasExplicit,
                    // A recognised carrier phrase is strong evidence of booking intent; a bare
                    // place name ("Peelamedu") is likely but less certain, so it scores lower.
                    confidence = if (extraction.hadCarrier) 0.95f else 0.72f
                ),
                phrase = extraction.destination,
                diagnostic = "CLASSIFY kind=BOOKING dest=\"${extraction.destination}\" " +
                        "rideType=${extraction.rideType.name} carrier=${extraction.hadCarrier}"
            )
        }

        return Classification(
            kind = UtteranceClass.UNKNOWN,
            phrase = text,
            diagnostic = "CLASSIFY kind=UNKNOWN utterance=\"$text\""
        )
    }

    private fun command(intent: RiderIntent, text: String, name: String) = Classification(
        kind = UtteranceClass.COMMAND,
        intent = intent,
        phrase = text,
        diagnostic = "CLASSIFY kind=COMMAND command=$name"
    )

    private fun themeFrom(text: String): ThemeChoice? {
        val lower = text.lowercase()
        return when {
            lower.contains("yellow") -> ThemeChoice.HIGH_CONTRAST_YELLOW
            lower.contains("light") || lower.contains("bright") -> ThemeChoice.HIGH_CONTRAST_LIGHT
            lower.contains("dark") || lower.contains("black") -> ThemeChoice.DEEP_DARK
            else -> null
        }
    }
}
