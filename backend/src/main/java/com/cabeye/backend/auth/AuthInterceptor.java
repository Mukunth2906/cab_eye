package com.cabeye.backend.auth;

import com.cabeye.backend.account.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves {@code Authorization: Bearer <token>} to an account on every request.
 *
 * <p>Two tiers:
 * <ul>
 *   <li>{@code /me/**} requires a valid token — 401 otherwise. That is everything personal:
 *       profile, memory, feedback, scheduled rides.</li>
 *   <li>Everything else is <i>optionally</i> authenticated. Ride commands still work without a
 *       token so the browser test page and the existing tests keep running; with one, the
 *       server knows exactly who booked or accepted.</li>
 * </ul>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessions;
    private final AccountService accounts;

    public AuthInterceptor(SessionService sessions, AccountService accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            sessions.resolve(header.substring(7))
                    .flatMap(s -> accounts.find(s.accountId))
                    .ifPresent(a -> request.setAttribute(CurrentAccount.ATTRIBUTE, a));
        }

        String path = request.getRequestURI();
        boolean personal = path.equals("/me") || path.startsWith("/me/");
        if (personal && CurrentAccount.of(request).isEmpty()
                && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Please sign in again.\"}");
            return false;
        }
        return true;
    }
}
