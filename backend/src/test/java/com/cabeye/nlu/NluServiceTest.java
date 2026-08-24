package com.cabeye.nlu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests pin the two things that would silently break the rider app.
 *
 * <p>First: the ambiguity rule. It is the only place where the system
 * decides to spend the user's time, and a regression here is invisible
 * in the UI — a wrong booking just looks like a booking.
 *
 * <p>Second: parity with {@code js/nlu.js}. The browser keeps its own
 * copy of the gazetteer and resolves locally when this service is
 * unreachable. If the two implementations diverge, the app quietly
 * behaves differently offline, which is the worst kind of bug to find in
 * a user study.
 */
class NluServiceTest {

    private final NluService nlu = new NluService(new InMemoryPlaceRepository());

    @Test
    @DisplayName("bare 'Anna Nagar' ties on score and the two are far apart — so ask")
    void ambiguousAndDivergentAsks() {
        Resolution r = nlu.resolve("take me to Anna Nagar");

        assertTrue(r.resolved());
        assertTrue(r.needsClarify(), "East and West tie and sit ~2 km apart");
        assertEquals(2, r.candidates().size());
        assertTrue(r.gap() < NluService.DELTA);
        assertTrue(r.divergenceKm() > NluService.DIVERGENCE_KM);
        assertTrue(r.prompt().contains("Anna Nagar East"));
    }

    @Test
    @DisplayName("naming the side removes the ambiguity — so book it")
    void unambiguousBooks() {
        Resolution r = nlu.resolve("take me to Anna Nagar East");

        assertTrue(r.resolved());
        assertFalse(r.needsClarify());
        assertEquals("Anna Nagar East", r.place().name());
    }

    @Test
    @DisplayName("a rule matched, so this is the fast path")
    void fastPathIsReported() {
        assertTrue(nlu.resolve("take me to Adyar").fastPath());
        assertTrue(nlu.resolve("book an auto to T Nagar").fastPath());
        assertTrue(nlu.resolve("I want to go to Velachery").fastPath());
        assertFalse(nlu.resolve("Adyar").fastPath(), "no rule; the whole utterance was scored");
    }

    @Test
    @DisplayName("Tamil-English code-mix hits the fast path too")
    void codeMixParses() {
        Resolution r = nlu.resolve("Velachery-ku poganum");
        assertTrue(r.fastPath());
        assertTrue(r.resolved());
        assertEquals("Velachery", r.place().name());
    }

    @Test
    @DisplayName("ride type comes from the word used, defaulting to auto")
    void rideTypeIsDetected() {
        assertEquals("auto", nlu.resolve("take me to Adyar").rideType());
        assertEquals("car",  nlu.resolve("book a cab to Adyar").rideType());
        assertEquals("bike", nlu.resolve("book a bike to Adyar").rideType());
    }

    @Test
    @DisplayName("nothing in the gazetteer comes close — say so rather than guessing")
    void nonsenseIsUnresolved() {
        Resolution r = nlu.resolve("take me to Reykjavik");
        assertFalse(r.resolved());
        assertTrue(r.prompt().toLowerCase().contains("didn't catch"));
    }

    @Test
    @DisplayName("a one-word answer settles the clarification")
    void clarificationIsAnswerable() {
        Resolution r = nlu.resolve("take me to Anna Nagar");
        List<Place> options = r.candidates();

        assertEquals(0, nlu.pickCandidate("east", options));
        assertEquals(1, nlu.pickCandidate("west", options));
        assertEquals(0, nlu.pickCandidate("the first one", options));
        assertEquals(1, nlu.pickCandidate("the second one", options));
        assertEquals(-1, nlu.pickCandidate("mmm what", options),
                "an answer that settles nothing must re-ask, not guess");
    }

    @Test
    @DisplayName("distance between the two Anna Nagars is what triggers the question")
    void divergenceIsMeasuredNotAssumed() {
        Place east = new Place("Anna Nagar East", 13.0878, 80.2183);
        Place west = new Place("Anna Nagar West", 13.0850, 80.1998);
        double km = east.kmTo(west);
        assertTrue(km > 1.5 && km < 3.0, "expected ~2 km, got " + km);
    }
}
