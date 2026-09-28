package com.cabeye.backend.admin;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.account.AccountService;
import com.cabeye.backend.account.Role;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The admin website's API. Same-origin only (no CORS), and every route except {@code login}
 * and {@code state} needs an admin token — see {@link AdminInterceptor}.
 *
 * <pre>
 *   GET  /admin                              → the website
 *   GET  /admin/api/state                    is the admin set up?
 *   POST /admin/api/login  {email,password}  → {token}
 *   POST /admin/api/logout
 *   GET  /admin/api/overview                 counts + alert set-up
 *   GET  /admin/api/cases?status=NEW
 *   GET  /admin/api/cases/{id}
 *   POST /admin/api/cases/{id}/status {status}
 *   POST /admin/api/cases/{id}/notes  {text}
 *   GET  /admin/api/rides                    newest first
 *   GET  /admin/api/rides/{id}               snapshot + full event history
 *   GET  /admin/api/drivers
 *   POST /admin/api/drivers/{id}/suspend {suspended, reason}
 *   GET  /admin/api/riders
 *   POST /admin/api/telegram/link | /telegram/check | /telegram/unlink
 *   POST /admin/api/alerts/test
 * </pre>
 */
@RestController
public class AdminController {

    private final AdminAuthService auth;
    private final CaseService cases;
    private final AlertService alerts;
    private final RideService rides;
    private final AccountService accounts;

    public AdminController(AdminAuthService auth, CaseService cases, AlertService alerts,
                           RideService rides, AccountService accounts) {
        this.auth = auth;
        this.cases = cases;
        this.alerts = alerts;
        this.rides = rides;
        this.accounts = accounts;
    }

    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<Void> page() {
        return ResponseEntity.status(302).header("Location", "/admin/index.html").build();
    }

    // -----------------------------------------------------------------------------------
    //  Sign-in
    // -----------------------------------------------------------------------------------

    @GetMapping("/admin/api/state")
    public Map<String, Object> state() {
        return Map.of("configured", auth.configured());
    }

    @PostMapping("/admin/api/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!auth.configured()) {
            return ResponseEntity.status(503).body(Map.of("error",
                    "The admin login is not set up yet. Add cabeye.admin.email and cabeye.admin.password to backend\\cabeye-secrets.properties and restart."));
        }
        try {
            return auth.login(str(body.get("email")), str(body.get("password")), request.getRemoteAddr())
                    .<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of("token", t, "email", auth.adminEmail())))
                    .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Wrong email or password.")));
        } catch (AdminAuthService.Locked e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/api/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.length() > 7) auth.logout(header.substring(7));
        return Map.of("ok", true);
    }

    @GetMapping("/admin/api/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("admin", admin(request));
        out.put("openCases", cases.openCount());
        out.put("urgentOpen", cases.list(null).stream()
                .filter(c -> c.urgent && c.status != AdminCase.Status.RESOLVED).count());
        out.put("activeRides", rides.all().stream().filter(r -> !r.phase().isTerminal()).count());
        out.put("alerts", alerts.state());
        return out;
    }

    // -----------------------------------------------------------------------------------
    //  Inbox
    // -----------------------------------------------------------------------------------

    @GetMapping("/admin/api/cases")
    public ResponseEntity<?> cases(@RequestParam(value = "status", required = false) String status) {
        AdminCase.Status s = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                s = AdminCase.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown status."));
            }
        }
        return ResponseEntity.ok(cases.list(s));
    }

    @GetMapping("/admin/api/cases/{id}")
    public ResponseEntity<?> caseDetail(@PathVariable String id) {
        return cases.get(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such case.")));
    }

    @PostMapping("/admin/api/cases/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable String id, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        AdminCase.Status s;
        try {
            s = AdminCase.Status.valueOf(str(body.get("status")).trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status must be NEW, ACKNOWLEDGED or RESOLVED."));
        }
        return cases.setStatus(id, s, admin(request)).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such case.")));
    }

    @PostMapping("/admin/api/cases/{id}/notes")
    public ResponseEntity<?> addNote(@PathVariable String id, @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        try {
            return cases.addNote(id, str(body.get("text")), admin(request)).<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such case.")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------------------
    //  Rides
    // -----------------------------------------------------------------------------------

    @GetMapping("/admin/api/rides")
    public List<Ride.Snapshot> rides(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return rides.all().stream()
                .map(Ride::snapshot)
                .sorted(Comparator.comparing(Ride.Snapshot::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 500)))
                .toList();
    }

    @GetMapping("/admin/api/rides/{id}")
    public ResponseEntity<?> ride(@PathVariable String id) {
        return rides.find(id).<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of(
                        "ride", r.snapshot(),
                        "events", r.eventsAfter(0))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such ride.")));
    }

    // -----------------------------------------------------------------------------------
    //  People
    // -----------------------------------------------------------------------------------

    @GetMapping("/admin/api/drivers")
    public List<Map<String, Object>> drivers() {
        return accounts.all(Role.DRIVER).stream().map(a -> {
            Map<String, Object> m = person(a);
            Account.DriverProfile d = a.driver == null ? new Account.DriverProfile() : a.driver;
            m.put("vehicleType", d.vehicleType);
            m.put("vehicleModel", d.vehicleModel);
            m.put("vehicleColour", d.vehicleColour);
            m.put("vehiclePlate", d.vehiclePlate);
            m.put("completedTrips", d.completedTrips);
            m.put("ratingAverage", d.ratingAverage);
            m.put("ratingCount", d.ratingCount);
            m.put("complaints", cases.complaintsAgainst(a.id));
            m.put("suspended", d.suspended);
            m.put("suspendedReason", d.suspendedReason);
            return m;
        }).toList();
    }

    @PostMapping("/admin/api/drivers/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable String id, @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        boolean suspended = Boolean.parseBoolean(str(body.get("suspended")));
        String reason = str(body.get("reason"));
        if (suspended && reason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Say why the driver is suspended."));
        }
        return accounts.setSuspended(id, suspended, reason)
                .<ResponseEntity<?>>map(a -> {
                    org.slf4j.LoggerFactory.getLogger(AdminController.class).warn(
                            "ADMIN {} driver={} by={} reason=\"{}\"",
                            suspended ? "SUSPENDED" : "RESTORED", id, admin(request), reason);
                    return ResponseEntity.ok(Map.of("id", a.id, "suspended", suspended));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such driver.")));
    }

    @GetMapping("/admin/api/riders")
    public List<Map<String, Object>> riders() {
        return accounts.all(Role.RIDER).stream().map(a -> {
            Map<String, Object> m = person(a);
            if (a.rider != null) {
                m.put("language", a.rider.language);
                m.put("emergencyContactName", a.rider.emergencyContactName);
                m.put("emergencyContactPhone", a.rider.emergencyContactPhone);
            }
            return m;
        }).toList();
    }

    private static Map<String, Object> person(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("name", a.name);
        m.put("phone", a.phone);
        m.put("createdAt", a.createdAt);
        m.put("lastLoginAt", a.lastLoginAt);
        return m;
    }

    // -----------------------------------------------------------------------------------
    //  Alerts set-up
    // -----------------------------------------------------------------------------------

    @PostMapping("/admin/api/telegram/link")
    public ResponseEntity<?> telegramLink() {
        try {
            return ResponseEntity.ok(alerts.startLink());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", message(e)));
        }
    }

    @PostMapping("/admin/api/telegram/check")
    public ResponseEntity<?> telegramCheck() {
        try {
            boolean linked = alerts.checkLink();
            return ResponseEntity.ok(Map.of("linked", linked, "alerts", alerts.state()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", message(e)));
        }
    }

    @PostMapping("/admin/api/telegram/unlink")
    public Map<String, Object> telegramUnlink() {
        alerts.unlink();
        return alerts.state();
    }

    @PostMapping("/admin/api/alerts/test")
    public Map<String, Object> testAlert() {
        return Map.of("result", alerts.test());
    }

    // -----------------------------------------------------------------------------------

    private static String admin(HttpServletRequest request) {
        Object a = request.getAttribute(AdminInterceptor.ATTRIBUTE);
        return a == null ? "admin" : a.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
