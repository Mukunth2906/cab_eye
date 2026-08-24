package com.cabeye.nlu;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fast path: pulling a destination and a ride type out of one
 * sentence with regular expressions, in microseconds.
 *
 * <p>Whether a rule matched is reported back to the caller as
 * {@code fastPath}, and the fraction of real utterances that hit it is
 * a number worth measuring: it says how much of this problem needs a
 * language model at all. Everything that misses falls through to
 * scoring the whole utterance, which usually still works — "Adyar" on
 * its own matches Adyar — it just costs more and is easier to fool.
 */
public final class IntentParser {

    /** Ordered by specificity. The first rule that matches wins. */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?:take|drop|bring)\\s+me\\s+(?:to|at)\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:i\\s+(?:want|need)\\s+to\\s+)?go\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("book\\s+(?:an?\\s+)?(?:auto|car|cab|bike|taxi)?\\s*(?:to|for)\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|\\s)to\\s+(.+)", Pattern.CASE_INSENSITIVE),
            // Tamil-English code-mix: "Velachery-ku poganum"
            Pattern.compile("(.+?)\\s*-?ku\\s+poganum", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern BIKE = Pattern.compile("\\bbike\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAR  = Pattern.compile("\\b(car|cab|taxi)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Words that carry no place information. Stripping them before
     * scoring stops "book an auto to T Nagar" from being penalised for
     * the four words that were never going to match a place name.
     */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(please|now|the|a|an|auto|car|cab|taxi|bike|ride)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PUNCT = Pattern.compile("[^\\w\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private IntentParser() { }

    public record Parsed(String query, String rideType, boolean fastPath) { }

    public static Parsed parse(String text) {
        String input = text == null ? "" : text;

        String rideType = BIKE.matcher(input).find() ? "bike"
                        : CAR.matcher(input).find()  ? "car"
                        : "auto";

        String query = null;
        boolean fastPath = false;
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(input);
            if (m.find() && m.group(1) != null && m.group(1).trim().length() > 1) {
                query = m.group(1);
                fastPath = true;
                break;
            }
        }
        if (query == null) {
            query = input;
        }

        String cleaned = NOISE.matcher(query).replaceAll(" ");
        cleaned = PUNCT.matcher(cleaned).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").trim();

        return new Parsed(cleaned, rideType, fastPath);
    }
}
