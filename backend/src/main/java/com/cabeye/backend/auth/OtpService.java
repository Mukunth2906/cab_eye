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
 * <h2>Delivery</h2>
 * When an SMS provider is set up ({@link SmsSender}: Twilio or Fast2SMS keys in
 * {@code cabeye-secrets.properties}), the code is texted to the phone for real and is never put
 * in the reply — the app reads it from the incoming message.
 *
 * <p>With no provider, or when the provider refuses, delivery falls back to <b>mock</b>: the
 * code is written to the backend log and — only while {@code cabeye.auth.otp.expose} is true
 * (the default for local demos) — returned in the {@code /auth/otp} reply as {@code devCode},
 * so the app can show it as an incoming message and fill it in. Set
 * {@code CABEYE_OTP_EXPOSE=false} before any public deployment.
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
    private final com.cabeye.backend.redis.RedisBridge redisBridge;
    private final SmsSender sms;

    public OtpService(@Value("${cabeye.auth.otp.expose:true}") boolean expose,
                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                      com.cabeye.backend.redis.RedisBridge redisBridge,
                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                      SmsSender sms) {
        this.expose = expose;
        this.redisBridge = redisBridge;
        this.sms = sms;
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
            return new Sent(false, wait, null, TTL_MS / 1000, "none");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String hashed = hash(key, code);
        pending.put(key, new Pending(hashed, now, now + TTL_MS, 0));
        if (redisBridge != null && redisBridge.isEnabled()) {
            redisBridge.saveOtp(key, hashed + ":" + (now + TTL_MS), TTL_MS / 1000);
        }
        boolean texted = deliver(role, phone, code);
        // A code that really went out by SMS is never also handed back: the phone has to read
        // the message, exactly as it will for every real rider.
        return new Sent(true, 0, !texted && expose ? code : null, TTL_MS / 1000, texted ? "sms" : "mock");
    }

    /** @return the verdict; a success consumes the code so it cannot be replayed */
    public Verdict verify(Role role, String phone, String code) {
        String key = key(role, phone);
        Pending p = pending.get(key);
        if (p == null && redisBridge != null && redisBridge.isEnabled()) {
            String redisVal = redisBridge.getOtp(key);
            if (redisVal != null && redisVal.contains(":")) {
                String[] parts = redisVal.split(":", 2);
                p = new Pending(parts[0], System.currentTimeMillis(), Long.parseLong(parts[1]), 0);
            }
        }
        if (p == null) return Verdict.NO_CODE;
        if (System.currentTimeMillis() > p.expiresAt) {
            pending.remove(key);
            if (redisBridge != null && redisBridge.isEnabled()) redisBridge.deleteOtp(key);
            return Verdict.EXPIRED;
        }
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        if (MessageDigest.isEqual(p.hash.getBytes(StandardCharsets.UTF_8),
                hash(key, digits).getBytes(StandardCharsets.UTF_8))) {
            pending.remove(key);
            if (redisBridge != null && redisBridge.isEnabled()) redisBridge.deleteOtp(key);
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

    /** @return true when the code was texted; false means mock delivery (log, and devCode). */
    private boolean deliver(Role role, String phone, String code) {
        if (sms != null && sms.configured()) {
            String failure = sms.send(phone, code);
            if (failure == null) {
                log.info("OTP role={} phone={} delivered by SMS ({})", role, Phone.masked(phone), sms.provider());
                return true;
            }
            log.warn("OTP role={} phone={} SMS failed ({}); falling back to mock delivery", role, Phone.masked(phone), failure);
        }
        log.info("OTP role={} phone={} code={} (mock delivery — no SMS sent)", role, Phone.masked(phone), code);
        return false;
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
     * @param delivery    "sms" when texted, "mock" when not, "none" when nothing was sent
     */
    public record Sent(boolean sent, long retryAfterSeconds, String devCode, long expiresInSeconds, String delivery) {}

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
