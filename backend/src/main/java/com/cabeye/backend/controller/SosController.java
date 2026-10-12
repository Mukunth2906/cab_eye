package com.cabeye.backend.controller;

import com.cabeye.backend.admin.AdminCase;
import com.cabeye.backend.admin.AlertService;
import com.cabeye.backend.admin.CaseService;
import com.cabeye.backend.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rider's SOS button.
 *
 * <pre>
 *   POST /sos   {"rideId": "…" (optional), "lat": 11.0, "lng": 76.9 (optional), "note": "…" (optional)}
 *        → 200 {"caseId": "sos-…", "alerted": true|false}
 * </pre>
 *
 * Not under {@code /me}: a guest rider, or one whose session expired, must still reach help,
 * so a token is used when present and never required. The case goes to the admin inbox as
 * urgent and is sent to Telegram and email ({@code alerted} says whether either is set up, so
 * the phone never claims an alert went out when none could).
 */
@RestController
@CrossOrigin(originPatterns = "*")
public class SosController {

    public record SosRequest(String rideId, Double lat, Double lng, String note) {}

    private final CaseService cases;
    private final AlertService alerts;

    public SosController(CaseService cases, AlertService alerts) {
        this.cases = cases;
        this.alerts = alerts;
    }

    @PostMapping("/sos")
    public ResponseEntity<Map<String, Object>> sos(HttpServletRequest request, @RequestBody(required = false) SosRequest body) {
        SosRequest b = body == null ? new SosRequest(null, null, null, null) : body;
        Double lat = valid(b.lat(), 90) ? b.lat() : null;
        Double lng = valid(b.lng(), 180) ? b.lng() : null;
        String riderId = CurrentAccount.of(request).map(a -> a.id).orElse(null);
        AdminCase c = cases.sos(riderId, b.rideId(), lat == null || lng == null ? null : lat, lat == null || lng == null ? null : lng, b.note());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", c.id);
        out.put("alerted", alerts.telegramLinked() || alerts.emailConfigured());
        return ResponseEntity.ok(out);
    }

    private static boolean valid(Double v, double limit) {
        return v != null && !v.isNaN() && Math.abs(v) <= limit;
    }
}
