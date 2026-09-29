package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The signed-in account's own profile — rider or driver, same routes.
 *
 * <pre>
 *   GET   /me                  → {"account":{…},"profileComplete":true}
 *   PATCH /me/profile          partial update, e.g. {"speechRate":1.4}
 *                              driver: {"vehicleModel":"Bajaj RE","vehiclePlate":"TN38AB1234"}
 *   POST  /me/logout-everywhere
 * </pre>
 *
 * <p>{@code /me/**} is guarded by {@code AuthInterceptor}; reaching a handler here means the
 * request carried a valid token.
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/me")
public class ProfileController {

    private final AccountService accounts;
    private final SessionService sessions;

    public ProfileController(AccountService accounts, SessionService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(view(accounts.find(a.id).orElse(a)));
    }

    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> update(HttpServletRequest request,
                                                      @RequestBody Map<String, Object> changes) {
        Account a = CurrentAccount.of(request).orElseThrow();
        try {
            return ResponseEntity.ok(view(accounts.updateProfile(a.id, changes)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout-everywhere")
    public ResponseEntity<Map<String, Object>> logoutEverywhere(HttpServletRequest request) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(Map.of("sessionsRevoked", sessions.revokeAll(a.id)));
    }

    private static Map<String, Object> view(Account a) {
        Map<String, Object> out = new HashMap<>();
        out.put("account", a);
        out.put("profileComplete", AuthController.profileComplete(a));
        return out;
    }
}
