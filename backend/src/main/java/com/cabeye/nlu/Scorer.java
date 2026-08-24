package com.cabeye.nlu;

/**
 * How well a spoken fragment matches a place name.
 *
 * <p>A deliberately crude token-prefix overlap, ported character for
 * character from {@code js/nlu.js} so the browser fallback and the
 * server cannot disagree. Prefix matching in both directions is what
 * makes "anna" hit "Anna Nagar East" and "velacheri" hit "Velachery"
 * without a phonetic index.
 *
 * <p>It is crude on purpose. The interesting claim in this system is
 * not that the matcher is clever — it is that a cheap matcher plus an
 * explicit rule about <em>when to ask</em> beats a clever matcher that
 * guesses silently. Improving this function would blur that result.
 */
public final class Scorer {

    private Scorer() { }

    public static double score(String query, String name) {
        String[] a = split(query);
        String[] b = split(name);
        if (a.length == 0) {
            return 0;
        }
        int hits = 0;
        for (String word : a) {
            for (String candidate : b) {
                if (candidate.startsWith(word) || word.startsWith(candidate)) {
                    hits++;
                    break;
                }
            }
        }
        return (double) hits / Math.max(a.length, b.length);
    }

    private static String[] split(String s) {
        String trimmed = s == null ? "" : s.toLowerCase().trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }
}
