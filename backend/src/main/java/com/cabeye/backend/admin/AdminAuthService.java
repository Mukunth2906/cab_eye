package com.cabeye.backend.admin;

import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in for the admin website. Completely separate from rider/driver accounts: an admin is
 * not a phone user, is never created through the apps, and an admin token is useless on the
 * app API (and the reverse).
 *
 * <p>The one admin comes from {@code cabeye-secrets.properties} (or environment variables):
 * {@code cabeye.admin.email} and {@code cabeye.admin.password}. Leave either blank and the
 * admin website stays locked — it fails closed.
 *
 * <p>The password is never compared as text: it is stretched with PBKDF2 once at start and
 * each attempt is stretched the same way and compared in constant time. Five wrong attempts
 * from one address lock that address out for ten minutes. Tokens are random, stored only as
 * SHA-256, and last twelve hours.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    static final long TTL_MS = 12L * 60 * 60 * 1000;
    static final int MAX_FAILURES = 5;
    static final long LOCK_MS = 10L * 60 * 1000;
    static final int MIN_PASSWORD = 8;

    private final String email;
    private final byte[] salt = new byte[16];
    private final byte[] passwordHash;
    private final Table<AdminSession> sessions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public AdminAuthService(@Value("${cabeye.admin.email:}") String email,
                            @Value("${cabeye.admin.password:}") String password,
                            DataDirectory data) {
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.sessions = data.table("admin_sessions", AdminSession.class);
        long now = System.currentTimeMillis();
        sessions.removeWhere(s -> s.expiresAt < now);

        if (this.email.isEmpty() || password == null || password.length() < MIN_PASSWORD) {
            this.passwordHash = null;
            log.warn("ADMIN website locked: set cabeye.admin.email and cabeye.admin.password "
                    + "(at least {} characters) in backend\\cabeye-secrets.properties", MIN_PASSWORD);
        } else {
            random.nextBytes(salt);
            this.passwordHash = stretch(password);
            log.info("ADMIN website ready for {} at http://localhost:8080/admin", this.email);
        }
    }

    public boolean configured() {
        return passwordHash != null;
    }

    public String adminEmail() {
        return email;
    }

    /** @return the raw token, or empty when refused. Throws {@link Locked} while locked out. */
    public Optional<String> login(String givenEmail, String givenPassword, String fromAddress) {
        if (!configured()) return Optional.empty();
        String who = fromAddress == null ? "?" : fromAddress;
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(who);
        if (until != null && until > now) throw new Locked((until - now + 59_999) / 60_000);

        boolean emailOk = givenEmail != null && email.equals(givenEmail.trim().toLowerCase(Locale.ROOT));
        // Always stretch, even for a wrong email, so timing does not reveal which part was wrong.
        byte[] attempt = stretch(givenPassword == null ? "" : givenPassword);
        boolean ok = MessageDigest.isEqual(attempt, passwordHash) && emailOk;

        if (!ok) {
            int[] count = failures.computeIfAbsent(who, k -> new int[1]);
            synchronized (count) {
                count[0]++;
                if (count[0] >= MAX_FAILURES) {
                    lockedUntil.put(who, now + LOCK_MS);
                    count[0] = 0;
                    log.warn("ADMIN_LOGIN_LOCKED from={} for {} min", who, LOCK_MS / 60_000);
                }
            }
            log.warn("ADMIN_LOGIN_FAILED from={}", who);
            return Optional.empty();
        }
        failures.remove(who);
        lockedUntil.remove(who);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AdminSession s = new AdminSession();
        s.email = email;
        s.createdAt = now;
        s.expiresAt = now + TTL_MS;
        sessions.put(sha256(token), s);
        log.info("ADMIN_LOGIN from={}", who);
        return Optional.of(token);
    }

    /** @return the admin's email for a live token */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank() || !configured()) return Optional.empty();
        String key = sha256(token.trim());
        Optional<AdminSession> s = sessions.get(key);
        if (s.isEmpty()) return Optional.empty();
        if (s.get().expiresAt < System.currentTimeMillis() || !email.equals(s.get().email)) {
            sessions.remove(key);
            return Optional.empty();
        }
        return Optional.of(s.get().email);
    }

    public void logout(String token) {
        if (token != null) sessions.remove(sha256(token.trim()));
    }

    private byte[] stretch(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Too many wrong attempts from this address. */
    public static class Locked extends RuntimeException {
        public final long minutes;

        Locked(long minutes) {
            super("Too many wrong attempts. Try again in " + minutes + " minute(s).");
            this.minutes = minutes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminSession {
        public String email;
        public long createdAt;
        public long expiresAt;

        public AdminSession() {}
    }
}
