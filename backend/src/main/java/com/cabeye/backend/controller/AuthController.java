package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.account.Phone;
import com.cabeye.backend.account.Role;
import com.cabeye.backend.auth.OtpService;
import com.cabeye.backend.auth.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phone + OTP sign-in, shared by the rider and the driver app.
 *
 * <pre>
 *   POST /auth/otp     {"phone":"9876543210","role":"RIDER"}
 *        → {"sent":true,"expiresInSeconds":300,"devCode":"123456"}   devCode only in mock mode
 *   POST /auth/verify  {"phone":"…","role":"RIDER","code":"123456","name":"Harshini"}
 *        → {"token":"…","account":{…},"isNew":true}
 *   POST /auth/logout  Authorization: Bearer …
 * </pre>
 *
 * <p>Every error carries an {@code error} string written to be spoken aloud by the rider app
 * unchanged — the app never has to translate a status code into a sentence.
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OtpService otp;
    private final SessionService sessions;
    private final AccountService accounts;

    public AuthController(OtpService otp, SessionService sessions, AccountService accounts) {
        this.otp = otp;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @PostMapping("/otp")
    public ResponseEntity<Map<String, Object>> sendOtp(@RequestBody Map<String, Object> body) {
        Optional<String> phone = Phone.normalize(str(body.get("phone")));
        if (phone.isEmpty()) {
            return error(400, "That doesn't sound like a ten digit mobile number. Please say it again.");
        }
        Role role = Role.parse(body.get("role"));
        OtpService.Sent sent = otp.send(role, phone.get());

        Map<String, Object> out = new HashMap<>();
        out.put("sent", sent.sent());
        out.put("expiresInSeconds", sent.expiresInSeconds());
        out.put("registered", accounts.findByPhone(role, phone.get()).isPresent());
        if (!sent.sent()) {
            out.put("retryAfterSeconds", sent.retryAfterSeconds());
            out.put("error", "A code was just sent. Please wait " + sent.retryAfterSeconds() + " seconds.");
            return ResponseEntity.status(429).body(out);
        }
        if (sent.devCode() != null) out.put("devCode", sent.devCode());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body) {
        Optional<String> phone = Phone.normalize(str(body.get("phone")));
        if (phone.isEmpty()) {
            return error(400, "That doesn't sound like a ten digit mobile number. Please say it again.");
        }
        Role role = Role.parse(body.get("role"));
        OtpService.Verdict verdict = otp.verify(role, phone.get(), str(body.get("code")));
        if (verdict != OtpService.Verdict.OK) {
            Map<String, Object> out = new HashMap<>();
            out.put("error", verdict.spoken());
            out.put("reason", verdict.name());
            return ResponseEntity.status(401).body(out);
        }

        AccountService.Login login = accounts.loginOrCreate(role, phone.get(), str(body.get("name")));
        String token = sessions.issue(login.account().id, role);
        log.info("LOGIN id={} role={} new={}", login.account().id, role, login.created());

        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("account", login.account());
        out.put("isNew", login.created());
        out.put("profileComplete", profileComplete(login.account()));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            sessions.revoke(authorization.substring(7));
        }
        return ResponseEntity.ok(Map.of("signedOut", true));
    }

    /** A rider needs a name; a driver also needs the vehicle a rider will be told to find. */
    static boolean profileComplete(Account a) {
        boolean named = a.name != null && !a.name.isBlank();
        if (a.role == Role.DRIVER) return named && a.driver != null && a.driver.isComplete();
        return named;
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String spoken) {
        return ResponseEntity.status(status).body(Map.of("error", spoken));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
