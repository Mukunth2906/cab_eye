package com.cabeye.backend.auth;

import com.cabeye.backend.account.Phone;
import com.cabeye.backend.account.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time codes for phone sign-in.
 *
 * <h2>Mock delivery</h2>
 * There is no SMS provider in this MVP. The code is written to the backend log, and — only while
 * {@code cabeye.auth.otp.expose} is true (the default for local demos) — returned in the
 * {@code /auth/otp} response as {@code devCode}, so the app can "auto-read" it exactly the way
 * the SMS Retriever API will once a real provider (MSG91, Twilio, Firebase) is plugged into
 * {@link #deliver}. Set {@code CABEYE_OTP_EXPOSE=false} before any public deployment.
 *
 * <h2>Why these limits</h2>
 * <ul>
 *   <li>6 digits, 5 minutes: long enough for a screen-reader user to hear it and confirm,
 *       short enough to be worthless once stale.</li>
 *   <li>5 attempts then the code is burned: a million combinations with five guesses is a
 *       1-in-200,000 chance, not a brute-force target.</li>
 *   <li>30 s between sends per number: stops a script from spamming a stranger's phone.</li>
 *   <li>Only a SHA-256 hash of the code is held, so a heap dump does not leak live codes.</li>
 * </ul>
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    static final long TTL_MS = 5 * 60 * 1000L;
    static final long RESEND_COOLDOWN_MS = 30 * 1000L;
    static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final boolean expose;

    public OtpService(@Value("${cabeye.auth.otp.expose:true}") boolean expose) {
        this.expose = expose;
    }

    /**
     * Issues a fresh code for (role, phone).
     *
     * @return the outcome; {@link Sent#devCode} is null unless exposure is switched on
     */
    public Sent send(Role role, String phone) {
        String key = key(role, phone);
        long now = System.currentTimeMillis();
        Pending previous = pending.get(key);
        if (previous != null && now - previous.sentAt < RESEND_COOLDOWN_MS) {
            long wait = (RESEND_COOLDOWN_MS - (now - previous.sentAt) + 999) / 1000;
            return new Sent(false, wait, null, TTL_MS / 1000);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.put(key, new Pending(hash(key, code), now, now + TTL_MS, 0));
        deliver(role, phone, code);
        return new Sent(true, 0, expose ? code : null, TTL_MS / 1000);
    }

    /** @return the verdict; a success consumes the code so it cannot be replayed */
    public Verdict verify(Role role, String phone, String code) {
        String key = key(role, phone);
        Pending p = pending.get(key);
        if (p == null) return Verdict.NO_CODE;
        if (System.currentTimeMillis() > p.expiresAt) {
            pending.remove(key);
            return Verdict.EXPIRED;
        }
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        if (MessageDigest.isEqual(p.hash.getBytes(StandardCharsets.UTF_8),
                hash(key, digits).getBytes(StandardCharsets.UTF_8))) {
            pending.remove(key);
            return Verdict.OK;
        }
        int attempts = p.attempts + 1;
        if (attempts >= MAX_ATTEMPTS) {
            pending.remove(key);
            return Verdict.LOCKED;
        }
        pending.put(key, new Pending(p.hash, p.sentAt, p.expiresAt, attempts));
        return Verdict.WRONG;
    }

    /** The seam for a real SMS provider. */
    private void deliver(Role role, String phone, String code) {
        log.info("OTP role={} phone={} code={} (mock delivery — no SMS sent)", role, Phone.masked(phone), code);
    }

    private static String key(Role role, String phone) {
        return role.name() + ":" + phone;
    }

    private static String hash(String key, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((key + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Pending(String hash, long sentAt, long expiresAt, int attempts) {}

    /**
     * @param sent        false when refused by the resend cooldown
     * @param retryAfterSeconds how long to wait when refused
     * @param devCode     the code itself, only in mock-exposure mode
     */
    public record Sent(boolean sent, long retryAfterSeconds, String devCode, long expiresInSeconds) {}

    public enum Verdict {
        OK, WRONG, EXPIRED, LOCKED, NO_CODE;

        /** A sentence the rider app can speak as-is. */
        public String spoken() {
            return switch (this) {
                case OK -> "Verified.";
                case WRONG -> "That code did not match. Please try again.";
                case EXPIRED -> "That code has expired. I will send a new one.";
                case LOCKED -> "Too many wrong attempts. I will send a new code.";
                case NO_CODE -> "No code was sent to that number yet.";
            };
        }
    }
}
