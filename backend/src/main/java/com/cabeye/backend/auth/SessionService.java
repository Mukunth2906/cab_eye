package com.cabeye.backend.auth;

import com.cabeye.backend.account.Role;
import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Long-lived sign-in tokens.
 *
 * <p>Opaque random tokens rather than JWTs: they need no signing library, and they can be
 * revoked — a rider who loses their phone can be signed out everywhere by deleting rows, which
 * a stateless JWT cannot offer until it expires.
 *
 * <p>Only the SHA-256 of each token is stored, so the sessions file is useless to someone who
 * copies it. The phone keeps the raw token in the Android Keystore and unlocks it with the
 * fingerprint, which is what makes every login after the first one zero-step for a blind rider.
 *
 * <p>90 days, sliding: each use pushes expiry out again, so a regular rider is never asked to
 * redo OTP, while an abandoned phone's token dies on its own.
 */
@Service
public class SessionService {

    static final long TTL_MS = 90L * 24 * 60 * 60 * 1000;
    /** Only rewrite the file for a sliding refresh once a day, not on every request. */
    static final long REFRESH_EVERY_MS = 24L * 60 * 60 * 1000;

    private final Table<Session> sessions;
    private final SecureRandom random = new SecureRandom();

    public SessionService(DataDirectory data) {
        this.sessions = data.table("sessions", Session.class);
        long now = System.currentTimeMillis();
        sessions.removeWhere(s -> s.expiresAt < now);
    }

    /** @return the raw token — the only time it exists outside the phone */
    public String issue(String accountId, Role role) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        Session s = new Session();
        s.accountId = accountId;
        s.role = role;
        s.createdAt = now;
        s.refreshedAt = now;
        s.expiresAt = now + TTL_MS;
        sessions.put(hash(token), s);
        return token;
    }

    public Optional<Session> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String key = hash(token.trim());
        long now = System.currentTimeMillis();
        Optional<Session> found = sessions.get(key);
        if (found.isEmpty()) return Optional.empty();
        Session s = found.get();
        if (s.expiresAt < now) {
            sessions.remove(key);
            return Optional.empty();
        }
        if (now - s.refreshedAt > REFRESH_EVERY_MS) {
            sessions.update(key, row -> {
                row.refreshedAt = now;
                row.expiresAt = now + TTL_MS;
                return row;
            });
        }
        return Optional.of(s);
    }

    public void revoke(String token) {
        if (token != null) sessions.remove(hash(token.trim()));
    }

    /** Signs an account out on every device. */
    public int revokeAll(String accountId) {
        return sessions.removeWhere(s -> accountId.equals(s.accountId));
    }

    private static String hash(String token) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Session {
        public String accountId;
        public Role role;
        public long createdAt;
        public long refreshedAt;
        public long expiresAt;

        public Session() {}
    }
}
