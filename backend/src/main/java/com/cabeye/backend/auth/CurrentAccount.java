package com.cabeye.backend.auth;

import com.cabeye.backend.account.Account;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * The signed-in account for this request, as resolved by {@link AuthInterceptor}.
 *
 * <p>Controllers ask this rather than trusting an {@code X-User-Id} header. The header is still
 * accepted when nobody is signed in, so the existing debug page and tests keep working, but a
 * valid token always wins — the server re-stamps identity from the session, never from the body.
 */
public final class CurrentAccount {

    static final String ATTRIBUTE = "cabeye.account";

    private CurrentAccount() {}

    public static Optional<Account> of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof Account a ? Optional.of(a) : Optional.empty();
    }

    /** The signed-in id if there is one, otherwise the legacy header value. */
    public static String idOr(HttpServletRequest request, String headerFallback) {
        return of(request).map(a -> a.id).orElse(headerFallback);
    }
}
