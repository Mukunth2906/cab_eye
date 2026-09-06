package com.cabeye.backend.controller;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liveness and identity endpoints.
 *
 * <p>{@code /health} is not only a developer convenience here — it is what the debug settings
 * screen's "Test connection" button calls, and the result is <b>spoken aloud</b>. So it must
 * stay cheap, unauthenticated and fast: a rider or tester who pasted the wrong ngrok URL needs
 * to hear that within a second, not wait on a timeout.
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping
public class HealthController {

    private final RideSessionManager sessions;
    private final RideService rides;

    public HealthController(RideSessionManager sessions, RideService rides) {
        this.sessions = sessions;
        this.rides = rides;
    }

    /** Liveness check. Open {@code /health} in a browser — if you see JSON, the backend is up. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("service", "cabeye-backend");
        body.put("activeRides", rides.all().size());
        body.put("openRequests", rides.openRequests().size());
        body.put("socketTopics", sessions.snapshot());
        return body;
    }

    /**
     * Identity echo. Confirms the {@code X-User-Id} / {@code X-Role} header convention is being
     * applied by whatever client is calling — the role toggle's quickest smoke test.
     */
    @GetMapping("/whoami")
    public Map<String, String> whoami(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "anonymous") String userId,
            @RequestHeader(value = "X-Role",    required = false, defaultValue = "RIDER")     String role) {
        return Map.of("userId", userId, "role", role);
    }

    /**
     * Every ride the process knows about, for eyeballing a demo without a client attached.
     *
     * <p>Mapped outside {@code /rides/**} on purpose. Spring would resolve {@code /rides/all}
     * correctly against {@code RideController}'s {@code /rides/{rideId}} — a literal segment
     * beats a template — but relying on that precedence to keep two controllers from colliding
     * is the kind of thing that is true until someone adds a ride whose id is "all".
     */
    @GetMapping("/all-rides")
    public List<Ride.Snapshot> allRides() {
        return rides.all().stream().map(Ride::snapshot).toList();
    }
}
