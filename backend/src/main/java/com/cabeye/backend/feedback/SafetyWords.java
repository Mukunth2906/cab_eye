package com.cabeye.backend.feedback;

import java.util.regex.Pattern;

/**
 * Server-side second look at a rider's own words, so a safety report is never left in the
 * ordinary queue just because the phone filed it under "other".
 *
 * <p>Found in the demo: "there was no OTP verification done" arrived as OTHER, not urgent. A
 * boarding code that was never checked means the rider could not be sure it was their car —
 * that is a safety report.
 */
public final class SafetyWords {

    private static final Pattern SAFETY = Pattern.compile(
            "\\b(unsafe|safety|scared|afraid|danger\\w*|rash|speeding|too fast|drunk|harass\\w*|touch\\w*|"
                    + "threat\\w*|followed|abus\\w*|accident|crash\\w*|inappropriate|misbehav\\w*|stalk\\w*|"
                    + "otp|o t p|boarding code|code (was )?(not|never)|no code|verification|verify|verified|"
                    + "wrong (car|driver|auto|vehicle|person|cab)|not my driver|different (car|driver|auto|vehicle|person|cab)|"
                    + "fake|emergency|sos|help me)\\b",
            Pattern.CASE_INSENSITIVE);

    private SafetyWords() {}

    public static boolean concerning(String text) {
        return text != null && SAFETY.matcher(text).find();
    }
}
