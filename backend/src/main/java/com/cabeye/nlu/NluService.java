package com.cabeye.nlu;

import java.util.Comparator;
import java.util.List;

/**
 * Turning one spoken sentence into a booking.
 *
 * <p>THE AMBIGUITY BUDGET — the only interesting rule in this service.
 *
 * <p>Every voice assistant faces the same choice when two interpretations
 * score alike: guess, or ask. Guessing is fast and occasionally sends
 * someone to the wrong side of a city. Asking is safe and costs a spoken
 * turn, which for a blind user is several seconds of the interface
 * talking at them instead of the task getting done.
 *
 * <p>The usual answer is a confidence threshold, which is the wrong
 * question — it measures how sure the matcher is, not how much being
 * wrong would hurt. This service measures the harm directly:
 *
 * <pre>
 *   scores tied  AND  places far apart   →  ask
 *   scores tied  AND  places close       →  don't ask, just book
 *   scores clear                         →  don't ask, just book
 * </pre>
 *
 * <p>"Anna Nagar East" against "Anna Nagar West" ties on score and sits
 * two kilometres apart: worth a question. Two entrances to the same
 * market tie on score and sit four hundred metres apart: not worth
 * making someone listen to a question, because the walk is shorter than
 * the conversation.
 *
 * <p>{@link #DELTA} and {@link #DIVERGENCE_KM} are the two numbers that
 * define that trade-off, and they are the two numbers to sweep when
 * reporting how the system behaves.
 */
public class NluService {

    /** Score difference below which two candidates count as tied. */
    public static final double DELTA = 0.16;

    /** Kilometres. Below this, being wrong is cheap, so don't ask. */
    public static final double DIVERGENCE_KM = 1.5;

    /** Below this score nothing in the gazetteer is a plausible match. */
    public static final double FLOOR = 0.28;

    private final PlaceRepository places;

    public NluService(PlaceRepository places) {
        this.places = places;
    }

    public Resolution resolve(String text) {
        IntentParser.Parsed parsed = IntentParser.parse(text);

        List<Ranked> ranked = places.findAll().stream()
                .map(p -> new Ranked(p, Scorer.score(parsed.query(), p.name())))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .toList();

        if (ranked.isEmpty() || ranked.get(0).score() < FLOOR) {
            return Resolution.unresolved(parsed.query(), parsed.rideType(), parsed.fastPath());
        }

        Ranked best = ranked.get(0);
        Ranked runnerUp = ranked.size() > 1 ? ranked.get(1) : null;

        double gap = best.score() - (runnerUp == null ? 0 : runnerUp.score());
        // No runner-up means nothing to be confused with; 99 km stands in
        // for "infinitely far", which reads better than a null check
        // scattered through the rule below.
        double divergence = runnerUp == null ? 99 : best.place().kmTo(runnerUp.place());
        boolean tied = gap < DELTA;

        if (tied && divergence > DIVERGENCE_KM) {
            return Resolution.clarify(List.of(best.place(), runnerUp.place()),
                    parsed.query(), parsed.rideType(), parsed.fastPath(), gap, divergence);
        }

        return Resolution.book(best.place(), parsed.query(), parsed.rideType(),
                parsed.fastPath(), gap, divergence, tied);
    }

    /**
     * Which of two offered candidates the spoken answer picked.
     * Returns the index, or -1 if the answer settled nothing and the
     * question has to be asked again.
     */
    public int pickCandidate(String answer, List<Place> candidates) {
        String text = answer == null ? "" : answer.toLowerCase();

        // Order matters, and getting it wrong is silent. "the second one"
        // contains the word "one", so testing `first` first sends the
        // rider to the wrong place while looking like it understood
        // perfectly. Ordinals lose to the more specific word.
        if (text.matches(".*\\b(second|two|2nd|latter|other)\\b.*")) {
            return 1;
        }
        if (text.matches(".*\\b(first|one|1st|former)\\b.*")) {
            return 0;
        }

        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            scores[i] = Scorer.score(text, candidates.get(i).name());
        }
        int best = scores[0] >= scores[1] ? 0 : 1;
        if (scores[best] >= 0.3 && Math.abs(scores[0] - scores[1]) > 0.05) {
            return best;
        }

        // "east" / "west" — the distinguishing last word on its own.
        for (int i = 0; i < candidates.size(); i++) {
            String tail  = lastWord(candidates.get(i).name());
            String other = lastWord(candidates.get(1 - i).name());
            if (!tail.equals(other) && text.matches(".*\\b" + tail + "\\b.*")) {
                return i;
            }
        }
        return -1;
    }

    private static String lastWord(String s) {
        String[] parts = s.toLowerCase().split("\\s+");
        return parts[parts.length - 1];
    }

    private record Ranked(Place place, double score) { }
}
