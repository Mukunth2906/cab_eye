package com.cabeye.backend.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards {@code /admin/api/**}: every call needs {@code Authorization: Bearer <admin token>},
 * except signing in and the public "is the admin set up?" check.
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "cabeye.admin";

    private final AdminAuthService auth;

    public AdminInterceptor(AdminAuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if (!path.startsWith("/admin/api/")) return true;
        if (path.equals("/admin/api/login") || path.equals("/admin/api/state")) return true;
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : null;
        String email = auth.resolve(token).orElse(null);
        if (email == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Please sign in again.\"}");
            return false;
        }
        request.setAttribute(ATTRIBUTE, email);
        return true;
    }
}
