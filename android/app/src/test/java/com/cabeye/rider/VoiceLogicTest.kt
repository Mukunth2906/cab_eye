package com.cabeye.rider

import com.cabeye.rider.dialogue.RecoveryLadder
import com.cabeye.rider.intent.Classifier
import com.cabeye.rider.intent.IntentParser
import com.cabeye.rider.intent.RiderIntent
import com.cabeye.rider.intent.Stopwords
import com.cabeye.rider.intent.UtteranceClass
import com.cabeye.rider.places.City
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.places.MatchGate
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.ThemeChoice
import com.cabeye.rider.ui.theme.CabEyeInk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM tests for the parts of the voice loop that need no device: classification, place
 * resolution, the matching gates, the ambiguity budget, the recovery ladder, and the contrast
 * floor on every theme.
 *
 * These are the pieces where a silent mistake would be hardest to notice on a phone — a
 * mis-scored place just books the wrong ride, with nothing on screen to indicate why, and the
 * rider finds out when they arrive somewhere else.
 *
 * The eight cases the brief specifies are gathered in [AcceptanceTests] at the bottom and each
 * is named after the utterance it covers, so a failure report reads as a sentence about the
 * product rather than as a stack trace.
 */
class VoiceLogicTest {

    /**
     * The demo build defaults to Coimbatore, but most of the brief's acceptance cases are
     * written against Chennai place names. Each test therefore states its city explicitly and
     * this restores the default afterwards — a leaked `activeCity` would make tests pass or
     * fail depending on the order JUnit happened to run them in.
     */
    @Before
    fun useChennai() {
        Gazetteer.activeCity = City.CHENNAI
    }

    @After
    fun restoreDefault() {
        Gazetteer.activeCity = City.COIMBATORE
    }

    // ===============================================================================
    //  Classification — the step that did not exist before
    // ===============================================================================

    @Test
    fun `greetings are greetings, not destinations`() {
        for (word in listOf("hi", "hello", "hey", "good morning", "vanakkam")) {
            val result = Classifier.classify(word, rideActive = false)
            assertEquals("`$word` must be a GREETING", UtteranceClass.GREETING, result.kind)
        }
    }

    @Test
    fun `a greeting in front of a real booking does not swallow the booking`() {
        // "hey take me to Adyar" is a booking with a friendly opener. Answering it with
        // "Hello. Just say where you want to go." would be maddening.
        val result = Classifier.classify("hey take me to Adyar", rideActive = false)
        assertEquals(UtteranceClass.BOOKING, result.kind)
        assertEquals("adyar", (result.intent as RiderIntent.Book).destinationQuery)
    }

    @Test
    fun `an unfinished booking sentence is INCOMPLETE, never UNKNOWN and never a booking`() {
        for (utterance in listOf("take me to", "i want to go to", "book a cab", "drop me at")) {
            val result = Classifier.classify(utterance, rideActive = false)
            assertEquals(
                "`$utterance` must be INCOMPLETE — the rider was perfectly clear, " +
                        "they just stopped talking",
                UtteranceClass.INCOMPLETE,
                result.kind
            )
            assertTrue(
                "`$utterance` must never produce a Book intent",
                result.intent !is RiderIntent.Book
            )
        }
    }

    @Test
    fun `an already-filled slot is carried across the follow-up question`() {
        // "book an auto to" must be answered with "Where would you like to go?" and NOT with a
        // fresh start that throws away the word "auto".
        val result = Classifier.classify("book an auto to", rideActive = false)
        assertEquals(UtteranceClass.INCOMPLETE, result.kind)
        assertEquals(RideType.AUTO, result.knownRideType)

        // ...whereas an unstated ride type must not be reported as known, or the app would be
        // claiming the rider said something they did not.
        val bare = Classifier.classify("take me to", rideActive = false)
        assertEquals(UtteranceClass.INCOMPLETE, bare.kind)
        assertNull("ride type was never stated, so it is not 'known'", bare.knownRideType)
    }

    @Test
    fun `commands are recognised and cancel outranks everything`() {
        assertEquals(RiderIntent.Cancel, Classifier.classify("cancel", true).intent)
        assertEquals(RiderIntent.Help, Classifier.classify("help", false).intent)
        assertEquals(RiderIntent.ListPlaces, Classifier.classify("list places", false).intent)
        assertEquals(RiderIntent.CallSupport, Classifier.classify("call support", false).intent)
        assertEquals(RiderIntent.BookAgain, Classifier.classify("book again", false).intent)
        assertEquals(RiderIntent.Repeat, Classifier.classify("say that again", false).intent)

        // "cancel" must never be read as a destination — the one promise optimistic booking
        // cannot break.
        for (word in listOf("cancel", "stop", "never mind", "forget it", "cancel it")) {
            assertEquals(
                "`$word` must parse as Cancel",
                RiderIntent.Cancel,
                Classifier.classify(word, rideActive = true).intent
            )
        }
    }

    @Test
    fun `call support is not swallowed by the help pattern`() {
        // "call the helpline" contains "help". Ordering, not cleverness, is what gets this right.
        assertEquals(RiderIntent.CallSupport, Classifier.classify("call the helpline", false).intent)
    }

    @Test
    fun `city and theme are switchable by voice`() {
        val city = Classifier.classify("switch to Coimbatore", false).intent
        assertTrue(city is RiderIntent.SwitchCity)
        assertEquals(City.COIMBATORE, City.fromSpoken((city as RiderIntent.SwitchCity).spokenCity))

        assertEquals(
            RiderIntent.SetTheme(ThemeChoice.HIGH_CONTRAST_YELLOW),
            Classifier.classify("yellow theme", false).intent
        )
        assertEquals(
            RiderIntent.SetTheme(ThemeChoice.HIGH_CONTRAST_LIGHT),
            Classifier.classify("switch to the light theme", false).intent
        )
    }

    @Test
    fun `mid-ride commands only fire when a ride is active`() {
        assertEquals(RiderIntent.Status, Classifier.classify("where is my driver", true).intent)
        assertTrue(Classifier.classify("where is my driver", false).intent !is RiderIntent.Status)
    }

    // ===============================================================================
    //  Stopwords
    // ===============================================================================

    @Test
    fun `no stopword is also a word in any place name`() {
        // A stopword that collided with a place-name token would make that place silently
        // unbookable, and the failure would look like a recognition problem rather than a
        // data problem — the worst kind to debug from a phone.
        for (city in City.entries) {
            for (place in city.places) {
                for (form in place.aliases + place.name) {
                    for (token in Stopwords.normalise(form).split(" ")) {
                        assertFalse(
                            "\"$token\" is both a stopword and part of ${city.displayName}'s " +
                                    "\"${place.name}\" — one of the two has to change",
                            token in Stopwords.WORDS
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an utterance of pure filler strips to nothing`() {
        assertTrue(Stopwords.isAllStopwords(Stopwords.normalise("to")))
        assertTrue(Stopwords.isAllStopwords(Stopwords.normalise("um the a please")))
        assertFalse(Stopwords.isAllStopwords(Stopwords.normalise("adyar")))
    }

    // ===============================================================================
    //  The gates
    // ===============================================================================

    @Test
    fun `gate 2 rejects a phrase that is nothing but stopwords`() {
        val decision = MatchGate.evaluate("to", Gazetteer.score("to"))
        assertTrue(decision is MatchGate.Decision.Reject)
        assertEquals(
            MatchGate.FailedGate.ALL_STOPWORDS,
            (decision as MatchGate.Decision.Reject).gate
        )
    }

    @Test
    fun `gate 1 rejects a phrase shorter than three characters`() {
        val decision = MatchGate.evaluate("ad", Gazetteer.score("ad"))
        assertTrue(decision is MatchGate.Decision.Reject)
        assertEquals(
            MatchGate.FailedGate.TOO_SHORT,
            (decision as MatchGate.Decision.Reject).gate
        )
    }

    @Test
    fun `gate 4 rejects a match that covers only a fragment of a place name`() {
        // A single-letter or partial-word overlap is arithmetic, not evidence. This is the gate
        // that makes the whole "to" -> "T Nagar" family of accidents unrepresentable, no matter
        // how the scorer is later tuned.
        val fragment = listOf(
            PlaceOption("T Nagar", 13.0418, 80.2341, score = 0.95f, coversWholeToken = false)
        )
        val decision = MatchGate.evaluate("tee", fragment)

        assertTrue("a 0.95 fragment match must still be rejected", decision is MatchGate.Decision.Reject)
        assertEquals(
            MatchGate.FailedGate.FRAGMENT_MATCH,
            (decision as MatchGate.Decision.Reject).gate
        )
    }

    @Test
    fun `every decision carries a diagnostic naming the branch`() {
        // The brief requires the score, the failing gate and the branch on EVERY utterance —
        // a gate that only logs its rejections cannot be shown to be calibrated.
        val cases = listOf("to", "ad", "adyar", "adyer", "buckingham palace")
        for (phrase in cases) {
            val decision = MatchGate.evaluate(phrase, Gazetteer.score(phrase))
            assertTrue(
                "\"$phrase\" produced an empty diagnostic",
                decision.diagnostic.isNotBlank()
            )
            assertTrue(
                "\"$phrase\" diagnostic must name a branch: ${decision.diagnostic}",
                decision.diagnostic.contains("branch=")
            )
        }
    }

    @Test
    fun `the near-miss band is exactly 0_30 up to 0_50`() {
        fun decisionFor(score: Float) = MatchGate.evaluate(
            "velachery",
            listOf(PlaceOption("Velachery", 12.9791, 80.2210, score, coversWholeToken = true))
        )

        assertTrue("0.50 is the floor to PROCEED", decisionFor(0.50f) is MatchGate.Decision.Proceed)
        assertTrue("just under 0.50 must ask", decisionFor(0.49f) is MatchGate.Decision.NearMiss)
        assertTrue("0.30 is still worth asking about", decisionFor(0.30f) is MatchGate.Decision.NearMiss)
    }

    // ===============================================================================
    //  Scoring
    // ===============================================================================

    @Test
    fun `exact and alias matches score highest`() {
        assertEquals("Adyar", Gazetteer.score("adyar").first().name)
        assertEquals("Chennai Central", Gazetteer.score("central").first().name)
        assertEquals("Chennai Airport", Gazetteer.score("airport").first().name)
        assertEquals("T Nagar", Gazetteer.score("thyagaraya nagar").first().name)
    }

    @Test
    fun `misrecognised spellings still resolve`() {
        // These are the forms on-device recognition actually produces for these names.
        assertEquals("Velachery", Gazetteer.score("velacheri").first().name)
        assertEquals("Guindy", Gazetteer.score("gindy").first().name)

        Gazetteer.activeCity = City.COIMBATORE
        assertEquals("Peelamedu", Gazetteer.score("pillamedu").first().name)
        assertEquals("Gandhipuram", Gazetteer.score("gandipuram").first().name)
        assertEquals("Ukkadam", Gazetteer.score("ukadam").first().name)
    }

    @Test
    fun `every example the app suggests actually books, in every city`() {
        // The ladder and the idle screen both name example places out loud. An example that
        // does not resolve would be the cruellest possible failure: the app telling a rider who
        // has already failed twice to say a word that it will also reject.
        for (city in City.entries) {
            Gazetteer.activeCity = city
            for (example in city.examples) {
                assertEquals(
                    "${city.displayName} suggests \"$example\" but cannot book it",
                    "BOOK:$example",
                    outcomeOf("take me to $example")
                )
            }
        }
    }

    @Test
    fun `unknown places resolve to nothing rather than to a wrong guess`() {
        assertTrue(Gazetteer.score("buckingham palace").isEmpty())
    }

    @Test
    fun `a place in another city is found on the failure path`() {
        Gazetteer.activeCity = City.COIMBATORE
        assertTrue("Adyar is not in Coimbatore", Gazetteer.score("adyar").isEmpty())

        val elsewhere = Gazetteer.findInOtherCities("adyar")
        assertNotNull("the failure path must be able to diagnose a cross-city request", elsewhere)
        assertEquals(City.CHENNAI, elsewhere!!.first)
        assertEquals("Adyar", elsewhere.second.name)
    }

    // ===============================================================================
    //  The ambiguity budget — unchanged from step 1, both branches
    // ===============================================================================

    /** `gap < DELTA && distance <= DIVERGE` -> don't ask, just book it. */
    @Test
    fun `tied but nearby candidates are booked without asking`() {
        // Anna Nagar East and Anna Nagar West Extension are the far pair; East and the two
        // Nagar-suffixed names nearby are not. Use an explicitly close pair: Besant Nagar and
        // Adyar are adjacent, ~1.0 km apart.
        val besant = Gazetteer.score("besant nagar").first { it.name == "Besant Nagar" }
        val adyar = Gazetteer.score("adyar").first { it.name == "Adyar" }

        val km = Gazetteer.distanceKm(besant, adyar)
        assertTrue("Besant Nagar and Adyar should be within DIVERGE, were $km km", km <= RiderState.DIVERGENCE_KM)

        val shouldAsk = 0.0f < RiderState.CONFIDENCE_DELTA && km > RiderState.DIVERGENCE_KM
        assertFalse("being wrong between two adjacent areas is not worth an interruption", shouldAsk)
    }

    /** `gap < DELTA && distance > DIVERGE` -> ask. */
    @Test
    fun `tied and distant candidates trip the gate and ask`() {
        // "anna" is genuinely ambiguous between Anna Nagar and Anna Salai, which are ~6 km
        // apart — here being wrong really would cost the rider.
        val results = Gazetteer.score("anna")
        val top = results[0]
        val runnerUp = results[1]

        val gap = top.score - runnerUp.score
        val divergenceKm = Gazetteer.distanceKm(top, runnerUp)

        assertTrue("scores should tie, gap was $gap", gap < RiderState.CONFIDENCE_DELTA)
        assertTrue("should diverge, was only $divergenceKm km", divergenceKm > RiderState.DIVERGENCE_KM)
    }

    @Test
    fun `an explicit anna nagar east does not trip the gate`() {
        val results = Gazetteer.score("anna nagar east")
        assertEquals("Anna Nagar East", results[0].name)

        val runnerUp = results.getOrNull(1)
        if (runnerUp != null) {
            val gap = results[0].score - runnerUp.score
            val km = Gazetteer.distanceKm(results[0], runnerUp)
            assertFalse(
                "naming the place explicitly should not ask (gap=$gap, km=$km)",
                gap < RiderState.CONFIDENCE_DELTA && km > RiderState.DIVERGENCE_KM
            )
        }
    }

    @Test
    fun `an unambiguous destination does not trip the gate`() {
        val results = Gazetteer.score("velachery")
        assertEquals("Velachery", results[0].name)
        val runnerUp = results.getOrNull(1)
        if (runnerUp != null) {
            assertTrue(
                "gap should be decisive, was ${results[0].score - runnerUp.score}",
                results[0].score - runnerUp.score >= RiderState.CONFIDENCE_DELTA
            )
        }
    }

    @Test
    fun `Coimbatore has its own genuinely ambiguous pair`() {
        // The demo needs a case that exercises the clarify gate in the city it runs in.
        // A bare "puram" is ambiguous between RS Puram and Gandhipuram, ~2.2 km apart.
        Gazetteer.activeCity = City.COIMBATORE

        val results = Gazetteer.score("puram")
        val top = results[0]
        val runnerUp = results[1]

        val gap = top.score - runnerUp.score
        val km = Gazetteer.distanceKm(top, runnerUp)

        assertEquals("RS Puram", top.name)
        assertEquals("Gandhipuram", runnerUp.name)
        assertTrue("scores should tie, gap was $gap", gap < RiderState.CONFIDENCE_DELTA)
        assertTrue("should diverge, was only $km km", km > RiderState.DIVERGENCE_KM)
    }

    // ===============================================================================
    //  The recovery ladder
    // ===============================================================================

    @Test
    fun `the ladder escalates and never repeats itself`() {
        val rungs = (1..3).map {
            RecoveryLadder.respond(it, "peelamedu", City.CHENNAI).message
        }

        assertEquals("three failures must produce three different sentences", 3, rungs.toSet().size)
        assertTrue("rung 1 must name the word back", rungs[0].contains("peelamedu"))
        assertTrue("rung 1 must name the city", rungs[0].contains("Chennai"))
        assertTrue("rung 2 must offer 'list places'", rungs[1].contains("list places"))
        assertTrue("rung 3 must offer help", rungs[2].contains("help"))
        assertTrue("rung 3 must offer a human", rungs[2].contains("call support"))
    }

    @Test
    fun `every rung of the ladder ends with an open microphone`() {
        // The rule: a failure must never end in silence. To a rider who cannot see the screen,
        // an app that apologises and goes quiet is indistinguishable from one that has crashed.
        for (attempt in 1..5) {
            assertTrue(
                "rung for attempt $attempt left the rider with nothing to do",
                RecoveryLadder.respond(attempt, "somewhere", City.COIMBATORE).openMicAfter
            )
        }
    }

    @Test
    fun `a cross-city request gets a diagnosis, not an apology`() {
        val recovery = RecoveryLadder.respond(
            attempt = 1,
            unrecognised = "peelamedu",
            city = City.CHENNAI,
            crossCity = City.COIMBATORE to "Peelamedu"
        )

        assertTrue(recovery.message.contains("Peelamedu"))
        assertTrue(recovery.message.contains("Coimbatore"))
        assertTrue(recovery.message.contains("Chennai"))
        assertTrue("must offer the way out", recovery.message.contains("switch to Coimbatore"))
        assertTrue(recovery.openMicAfter)
    }

    @Test
    fun `list places is grouped into fives and stays interruptible`() {
        val groups = RecoveryLadder.listPlaces(City.COIMBATORE)
        assertTrue("a sixteen-place list must not be one utterance", groups.size > 1)
        assertTrue("the city must be named", groups.first().contains("Coimbatore"))

        // Every place has to actually appear somewhere, or the list is a lie.
        val spoken = groups.joinToString(" ")
        for (place in City.COIMBATORE.places) {
            assertTrue("\"${place.name}\" is missing from the spoken list", spoken.contains(place.name))
        }
    }

    @Test
    fun `the silence timeout is eight seconds and rests rather than looping`() {
        assertEquals(8_000L, RecoveryLadder.SILENCE_TIMEOUT_MS)
        assertTrue(
            "the re-prompt has to be a question, because it is followed by an open mic",
            RecoveryLadder.SILENCE_REPROMPT.contains("?")
        )
        assertTrue(
            "the resting line must tell the rider the word that wakes it",
            RecoveryLadder.SILENCE_REST.contains("hello")
        )
    }

    // ===============================================================================
    //  Themes
    // ===============================================================================

    @Test
    fun `every informational colour pair clears WCAG AAA in every theme`() {
        for (pair in CabEyeInk.INFORMATIONAL_PAIRS) {
            val ratio = CabEyeInk.contrastRatio(pair.foreground, pair.background)
            assertTrue(
                "${pair.name} measures %.2f:1, below the AAA floor of %.1f:1"
                    .format(ratio, CabEyeInk.AAA_FLOOR),
                ratio >= CabEyeInk.AAA_FLOOR
            )
        }
    }

    @Test
    fun `both dark themes use true black, not dark grey`() {
        // Not a stylistic preference. Dark grey causes halation — the light-bleed that makes
        // bright text smear for people with cataracts or corneal scarring — and this audience
        // was selected for having difficulty seeing.
        assertEquals(0xFF000000, CabEyeInk.DD_BACKGROUND)
        assertEquals(0xFF000000, CabEyeInk.HCY_BACKGROUND)
        assertEquals(0xFFFFFFFF, CabEyeInk.HCL_BACKGROUND)
    }

    @Test
    fun `the primary pairs are far above the floor, not scraping it`() {
        // AAA is a floor for the general population, not a target for this app.
        val primaries = listOf(
            CabEyeInk.contrastRatio(CabEyeInk.DD_ON_BACKGROUND, CabEyeInk.DD_BACKGROUND),
            CabEyeInk.contrastRatio(CabEyeInk.HCY_ON_BACKGROUND, CabEyeInk.HCY_BACKGROUND),
            CabEyeInk.contrastRatio(CabEyeInk.HCL_ON_BACKGROUND, CabEyeInk.HCL_BACKGROUND)
        )
        for (ratio in primaries) {
            assertTrue("primary pair only measured %.2f:1".format(ratio), ratio >= 14.0)
        }
    }

    // ===============================================================================
    //  The brief's eight acceptance cases
    // ===============================================================================

    /**
     * The eight cases stated in the brief, each run end to end from the raw utterance through
     * classification, extraction, scoring and the gates — the same path the device takes.
     *
     * Chennai is active for these, because that is the city they are written against. The demo
     * build defaults to Coimbatore, so [coimbatoreAcceptance] repeats the shape of them there.
     */
    private fun outcomeOf(utterance: String): String {
        val classification = Classifier.classify(utterance, rideActive = false)

        return when (classification.kind) {
            UtteranceClass.GREETING -> "GREETING"
            UtteranceClass.COMMAND -> "COMMAND"
            UtteranceClass.INCOMPLETE -> "ASK_DESTINATION"
            UtteranceClass.UNKNOWN -> "LADDER"
            UtteranceClass.BOOKING -> {
                val book = classification.intent as RiderIntent.Book
                when (val d = MatchGate.evaluate(book.rawDestination, Gazetteer.score(book.destinationQuery))) {
                    is MatchGate.Decision.Reject -> "LADDER"
                    is MatchGate.Decision.NearMiss -> "ASK_YES_NO:${d.candidate.name}"
                    is MatchGate.Decision.Proceed -> {
                        val runnerUp = d.runnerUp
                        val ask = runnerUp != null &&
                                (d.top.score - runnerUp.score) < RiderState.CONFIDENCE_DELTA &&
                                Gazetteer.distanceKm(d.top, runnerUp) > RiderState.DIVERGENCE_KM
                        if (ask) "ASK_WHICH:${d.top.name}|${runnerUp!!.name}"
                        else "BOOK:${d.top.name}"
                    }
                }
            }
        }
    }

    @Test
    fun `acceptance - take me to - asks for a destination and does not book`() {
        assertEquals("ASK_DESTINATION", outcomeOf("take me to"))
    }

    @Test
    fun `acceptance - to - asks for a destination and is never T Nagar`() {
        // The headline bug. A dangling preposition is the signature of a rider trailing off,
        // and it must be answered with a question — never with a cab.
        assertEquals("ASK_DESTINATION", outcomeOf("to"))

        val outcome = outcomeOf("to")
        assertFalse("a bare preposition must never reach T Nagar", outcome.contains("T Nagar"))
        assertFalse("a bare preposition must never book anything", outcome.startsWith("BOOK"))

        // And the same for the rest of the family, in both cities.
        for (city in City.entries) {
            Gazetteer.activeCity = city
            for (filler in listOf("to", "the", "a", "please", "um", "to the")) {
                val result = outcomeOf(filler)
                assertFalse(
                    "\"$filler\" booked something in ${city.displayName}: $result",
                    result.startsWith("BOOK")
                )
            }
        }
    }

    @Test
    fun `acceptance - hi - greets and then asks where to go`() {
        assertEquals("GREETING", outcomeOf("hi"))
    }

    @Test
    fun `acceptance - take me to Peelamedu - names it, names the city, offers an example`() {
        // Chennai is active, so Peelamedu cannot resolve — and the response must say why.
        assertEquals("LADDER", outcomeOf("take me to Peelamedu"))

        val crossCity = Gazetteer.findInOtherCities("peelamedu")
        assertNotNull("Peelamedu must be recognised as real, just elsewhere", crossCity)

        val recovery = RecoveryLadder.respond(
            attempt = 1,
            unrecognised = "peelamedu",
            city = City.CHENNAI,
            crossCity = crossCity!!.first to crossCity.second.name
        )
        assertTrue("must say the word back", recovery.message.contains("Peelamedu"))
        assertTrue("must say which city it knows", recovery.message.contains("Chennai"))
        assertTrue("must offer a route forward", recovery.message.contains("switch to Coimbatore"))

        // And without the cross-city hint, the plain first rung still names word, city and an
        // example — the brief's minimum.
        val plain = RecoveryLadder.respond(1, "peelamedu", City.CHENNAI)
        assertTrue(plain.message.contains("peelamedu"))
        assertTrue(plain.message.contains("Chennai"))
        assertTrue("must offer a concrete example", plain.message.contains("Adyar"))
    }

    @Test
    fun `acceptance - take me to Adyer - is a near miss, not a silent booking`() {
        // The exact bug: 0.44 against Adyar. Step 1 booked it, because 0.44 > 0.25.
        assertEquals("ASK_YES_NO:Adyar", outcomeOf("take me to Adyer"))
    }

    @Test
    fun `acceptance - take me to Adyar - books`() {
        assertEquals("BOOK:Adyar", outcomeOf("take me to Adyar"))
    }

    @Test
    fun `acceptance - take me to Anna Nagar - asks East or West`() {
        val outcome = outcomeOf("take me to Anna Nagar")
        assertTrue("expected a which-one question, got $outcome", outcome.startsWith("ASK_WHICH"))
        assertTrue(outcome.contains("Anna Nagar East"))
        assertTrue(outcome.contains("Anna Nagar West"))

        // The gate is only right to ask because they are genuinely far apart. This pins the
        // coordinate: an edit that moved them back under DIVERGE would silently turn the
        // question off and start booking one at random again.
        val results = Gazetteer.score("anna nagar")
        val km = Gazetteer.distanceKm(
            results.first { it.name == "Anna Nagar East" },
            results.first { it.name == "Anna Nagar West" }
        )
        assertTrue("the two Anna Nagars must be over DIVERGE apart, were $km km", km > RiderState.DIVERGENCE_KM)
    }

    @Test
    fun `acceptance - three failures in a row offer help or support, never silence`() {
        val third = RecoveryLadder.respond(3, "somewhere", City.CHENNAI)
        assertTrue(third.message.contains("help"))
        assertTrue(third.message.contains("call support"))
        assertTrue("and it still listens afterwards", third.openMicAfter)
    }

    // ===============================================================================
    //  The same shape, in the city the demo actually runs in
    // ===============================================================================

    @Test
    fun `coimbatoreAcceptance`() {
        Gazetteer.activeCity = City.COIMBATORE

        assertEquals("BOOK:Peelamedu", outcomeOf("take me to Peelamedu"))
        assertEquals("BOOK:RS Puram", outcomeOf("take me to RS Puram"))
        assertEquals("BOOK:Gandhipuram", outcomeOf("Gandhipuram-ku poganum"))
        assertEquals("ASK_DESTINATION", outcomeOf("take me to"))
        assertEquals("GREETING", outcomeOf("hello"))

        // The Coimbatore ambiguity pair.
        val ambiguous = outcomeOf("take me to puram")
        assertTrue("expected a which-one question, got $ambiguous", ambiguous.startsWith("ASK_WHICH"))

        // And a Chennai place, from Coimbatore, must not book.
        val crossCity = outcomeOf("take me to Adyar")
        assertEquals("LADDER", crossCity)
    }

    @Test
    fun `carrier phrases including Tamil code-switching are stripped`() {
        val cases = mapOf(
            "take me to Adyar" to "adyar",
            "I want to go to Velachery" to "velachery",
            "book an auto to T Nagar" to "t nagar",
            "drop me at Besant Nagar" to "besant nagar",
            "get me a cab to the airport" to "airport",
            // "-ku poganum" is how this is actually said, and it comes after the place name.
            "Velachery-ku poganum" to "velachery"
        )

        for ((utterance, expected) in cases) {
            val extraction = IntentParser.extract(utterance)
            assertEquals("`$utterance`", expected, extraction.destination)
        }
    }

    @Test
    fun `ride type is extracted and defaults to auto`() {
        assertEquals(RideType.AUTO, IntentParser.extract("take me to Adyar").rideType)
        assertFalse(
            "an unstated ride type must not be reported as explicit",
            IntentParser.extract("take me to Adyar").rideTypeWasExplicit
        )
        assertEquals(RideType.CAB, IntentParser.extract("book a cab to Adyar").rideType)
        assertTrue(IntentParser.extract("book a cab to Adyar").rideTypeWasExplicit)
        assertEquals(RideType.BIKE, IntentParser.extract("bike to Adyar").rideType)
    }
}
