package com.cabeye.backend.account;

import java.util.Optional;

/**
 * Indian mobile numbers in one canonical form: ten digits, no country code, no spaces.
 *
 * <p>A blind rider dictates the number, so the recogniser can hand back "+91 98765 43210",
 * "098765 43210" or "9 8 7 6 5 4 3 2 1 0". All of those are the same account, and treating
 * them as different would create a second, empty account with none of the rider's memory.
 */
public final class Phone {

    private Phone() {}

    /** @return the ten-digit number, or empty if this cannot be an Indian mobile number */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() != 10) return Optional.empty();
        // Indian mobile numbers start 6, 7, 8 or 9.
        char first = digits.charAt(0);
        if (first < '6') return Optional.empty();
        return Optional.of(digits);
    }

    /** "98765 43210" → "ending 3210", for logs and for speech — never the whole number. */
    public static String masked(String tenDigits) {
        if (tenDigits == null || tenDigits.length() < 4) return "unknown";
        return "******" + tenDigits.substring(tenDigits.length() - 4);
    }
}
