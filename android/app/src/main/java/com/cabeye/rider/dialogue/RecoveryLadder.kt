package com.cabeye.rider.dialogue

import com.cabeye.rider.places.City
import com.cabeye.rider.places.Gazetteer

/**
 * What the app should say and do after a failure.
 *
 * @param message the sentence to speak
 * @param openMicAfter whether to reopen the microphone the instant the sentence ends.
 *
 *   This is the mechanical expression of the product's second rule: **if the app asks a
 *   question out loud, it listens for the answer out loud.** Every rung below that ends in a
 *   question sets this true, and the one place it is false is the deliberate resting state at
 *   the very bottom, where the app has stopped asking anything.
 * @param rung which step of the ladder produced this, for the log
 */
data class Recovery(
    val message: String,
    val openMicAfter: Boolean,
    val rung: Int
)

/**
 * The escalating response to repeated failure.
 *
 * ## The rule
 * **A failure must never end in silence.** To someone who cannot see the screen, an app that
 * says "Sorry, I didn't catch that" and then goes quiet is indistinguishable from an app that
 * has crashed. There is no spinner to look at, no greyed-out button, no way at all to tell the
 * difference between "thinking", "waiting for you", and "broken".
 *
 * ## Why it escalates instead of repeating
 * Repeating the same sentence is worse than useless — it tells the rider the app did not
 * understand *and* that it has nothing else to offer. Each rung therefore changes strategy:
 * name the problem, then change the approach, then offer a way out entirely.
 *
 * ## Why reopening the microphone is safe here, when it was not before
 * Step 1 tried this once, as `askAgain()`, and it was removed after device testing: it looped,
 * the app talked over itself for six cycles, and a rider pressing to retry had their sentence
 * cut off after ~15 ms because the app had already opened a competing session. That was a real
 * failure and the reasoning behind removing it was sound. Four things make this version
 * different, and all four are necessary:
 *
 *  1. It **escalates and terminates**. After [MAX_RUNG] the ladder stops asking and rests.
 *  2. The microphone only ever reopens from the narrator's `onDone` callback, so the app can
 *     never be listening while it is still speaking.
 *  3. The silence timeout re-prompts exactly **once**, then goes quiet on purpose.
 *  4. A deliberate press still cancels any app-opened session, so the rider always wins.
 *
 * ## Purity
 * No Android dependencies, so every rung and every transition is provable by JVM unit test.
 * The view model owns the counter's lifetime; this object owns only what to say.
 */
object RecoveryLadder {

    /** After this rung the app stops escalating and rests. */
    const val MAX_RUNG = 3

    /** How long an open microphone may hear nothing before the app checks in. Brief: 8 s. */
    const val SILENCE_TIMEOUT_MS = 8_000L

    /** How many place names to speak at a time, so the list stays interruptible. */
    const val PLACES_PER_GROUP = 5

    /**
     * Builds the response for the *nth* consecutive failure.
     *
     * @param attempt 1-based count of consecutive failures, including this one
     * @param unrecognised what the rider actually said, as best the recogniser heard it. Spoken
     *   back verbatim on the first rung — see [firstRung] for why that matters more than it
     *   might appear to.
     * @param city the gazetteer's active city, named so the rider learns the app's actual scope
     * @param crossCity a real place the rider named that exists in a **different** city. When
     *   present it replaces the generic first rung entirely, because it is a precise diagnosis
     *   rather than a guess.
     */
    fun respond(
        attempt: Int,
        unrecognised: String,
        city: City,
        crossCity: Pair<City, String>? = null
    ): Recovery = when {
        crossCity != null -> Recovery(
            message = crossCityMessage(crossCity.second, crossCity.first, city),
            openMicAfter = true,
            rung = 1
        )

        attempt <= 1 -> Recovery(firstRung(unrecognised, city), openMicAfter = true, rung = 1)
        attempt == 2 -> Recovery(SECOND_RUNG, openMicAfter = true, rung = 2)
        // The last rung deliberately does NOT reopen the microphone. Three failed attempts in
        // a row, each followed by the mic springing open again, is the app talking over the
        // rider's own thinking time — and on device it reads as a microphone that never turns
        // off. The app has been wrong three times; the honest move is to stop and hand control
        // back. The message says exactly how to resume, so the silence is explained silence.
        else -> Recovery(THIRD_RUNG, openMicAfter = false, rung = MAX_RUNG)
    }

    /**
     * First failure: name the actual reason.
     *
     * The unrecognised word is said back deliberately. A generic "I didn't catch that" hides
     * *which* of two completely different failures happened — the recogniser mis-heard the
     * word, or the word is a real place this app does not cover — and those need opposite
     * responses from the rider. One is fixed by speaking more clearly; the other never will be,
     * and a rider repeating themselves into an app that cannot ever succeed is the most
     * demoralising failure mode this product has.
     *
     * Hearing "I don't know Peelamedu" also tells the rider the microphone and recogniser are
     * working perfectly, which "Sorry, I didn't catch that" actively conceals.
     */
    private fun firstRung(unrecognised: String, city: City): String {
        val examples = city.examples.joinToString(" or ")

        return if (unrecognised.isBlank()) {
            "I didn't catch a place there. Right now I only know places in " +
                    "${city.displayName}. Try an area like $examples."
        } else {
            "I don't know ${unrecognised.trim()}. Right now I only know places in " +
                    "${city.displayName}. Try an area like $examples."
        }
    }

    /**
     * The rider named a real place — in the wrong city.
     *
     * This is the single most useful thing the ladder can say, because it is the one failure
     * where the rider has done nothing wrong at all and no amount of repeating will help.
     */
    private fun crossCityMessage(place: String, itsCity: City, activeCity: City): String =
        "$place is in ${itsCity.displayName}. I'm set to ${activeCity.displayName}. " +
                "Say 'switch to ${itsCity.displayName}', or name a place in ${activeCity.displayName}."

    /**
     * Second failure: change the strategy.
     *
     * Saying the same thing again would confirm only that the app has one idea. This offers two
     * routes it has not offered yet — a different kind of answer, and a way to hear the whole
     * list.
     */
    private const val SECOND_RUNG =
        "Still not finding it. You can say a nearby landmark, " +
                "or say 'list places' to hear what I know."

    /**
     * Third failure: offer a way out.
     *
     * At this point the app has been wrong three times and should stop pretending the next
     * attempt will go better. Both options here lead somewhere other than another guess.
     */
    private const val THIRD_RUNG =
        "I'm having trouble understanding. Hold the screen and try again when you're ready, " +
                "or say 'call support' to talk to a person."

    // =================================================================================
    //  Silence
    // =================================================================================

    /**
     * First response to [SILENCE_TIMEOUT_MS] of nothing on an open microphone.
     *
     * Asked once, and it is a real question, so the microphone stays open for the answer.
     */
    const val SILENCE_REPROMPT = "Are you still there? Say where you'd like to go."

    /**
     * Second silence: stop asking.
     *
     * The app closes the loop out loud rather than just falling quiet, so the silence that
     * follows is *explained* silence. The rider now knows the app is idle rather than broken,
     * and knows the exact word that wakes it. This is the one place in the whole design where
     * the app says something and does not then listen — and it says so.
     */
    const val SILENCE_REST = "I'll wait. Say 'hello' when you're ready."

    // =================================================================================
    //  list places
    // =================================================================================

    /**
     * The gazetteer, in speakable groups.
     *
     * Grouped rather than read as one long sentence so the rider can interrupt part way
     * through — a sixteen-item list read without a break costs about forty seconds, and by then
     * the rider has usually heard the one they wanted and is waiting on the app instead of the
     * other way round. Each group is a separate utterance, and a press cancels the rest.
     */
    fun listPlaces(city: City = Gazetteer.activeCity): List<String> {
        val names = city.places.map { it.name }
        val groups = names.chunked(PLACES_PER_GROUP)

        return groups.mapIndexed { index, group ->
            val body = group.joinToString(", ")
            when {
                index == 0 && groups.size == 1 -> "In ${city.displayName} I know $body."
                index == 0 -> "In ${city.displayName} I know $body."
                index == groups.lastIndex -> "And $body. Say any of those, or press and hold to start over."
                else -> body + "."
            }
        }
    }
}
