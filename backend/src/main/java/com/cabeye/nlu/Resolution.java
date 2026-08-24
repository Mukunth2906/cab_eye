package com.cabeye.nlu;

import java.util.List;

/**
 * What the resolver decided, and — just as importantly — why.
 *
 * <p>The {@code gap} and {@code divergenceKm} fields are not debug
 * output. They are the evidence for the one decision this service makes
 * that a user can feel: whether to spend a spoken turn asking which
 * place was meant. Returning them lets the rider app print the rule
 * firing in its own log, which is how the behaviour stays auditable
 * instead of feeling arbitrary.
 *
 * @param resolved      false when nothing in the gazetteer came close
 * @param needsClarify  true when the caller should ask, not book
 * @param place         the destination, when there is one
 * @param candidates    the two contenders, when a question is needed
 * @param prompt        the exact sentence to speak, so the wording lives
 *                      in one place rather than being reinvented per client
 * @param tied          scores were close, but the places were near enough
 *                      that guessing was cheaper than asking
 */
public record Resolution(
        boolean resolved,
        boolean needsClarify,
        Place place,
        List<Place> candidates,
        String query,
        String rideType,
        boolean fastPath,
        double gap,
        double divergenceKm,
        boolean tied,
        String prompt
) {

    public static Resolution unresolved(String query, String rideType, boolean fastPath) {
        return new Resolution(false, false, null, List.of(), query, rideType, fastPath,
                0, 0, false,
                "Sorry, I didn't catch a place I know. Please say it again.");
    }

    public static Resolution book(Place place, String query, String rideType,
                                  boolean fastPath, double gap, double divergenceKm, boolean tied) {
        return new Resolution(true, false, place, List.of(), query, rideType, fastPath,
                gap, divergenceKm, tied,
                "Booking " + rideType + " to " + place.name() + ". Say cancel to stop.");
    }

    public static Resolution clarify(List<Place> candidates, String query, String rideType,
                                     boolean fastPath, double gap, double divergenceKm) {
        String prompt = "Did you mean " + candidates.get(0).name()
                + ", or " + candidates.get(1).name() + "?";
        return new Resolution(true, true, null, candidates, query, rideType, fastPath,
                gap, divergenceKm, true, prompt);
    }
}
