package com.cabeye.backend.controller;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.model.RideEventType;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Debug event injection — <b>three independent locks</b>.
 *
 * <p>What this endpoint used to be: an unauthenticated {@code POST} that would push any event
 * of any type onto any ride topic. Against this particular product that is not a routine
 * hardening gap. The rider is blind and takes the app at its word; anyone who could reach the
 * server could make a stranger's phone announce "your car has arrived" and send them walking
 * toward a car that is not there. The narration <em>is</em> the interface, so the ability to
 * forge narration is the ability to forge reality.
 *
 * <h2>The three layers, and why each one alone is not enough</h2>
 * <ol>
 *   <li><b>Profile gate.</b> {@code @Profile("dev")} means this bean is not created at all in a
 *       release build, so there is no route to reach. A token alone would still leave a live
 *       endpoint in production whose only protection is a secret that might leak.</li>
 *
 *   <li><b>Shared secret.</b> {@code X-Debug-Token} must match the {@code CABEYE_DEBUG_TOKEN}
 *       environment variable. A mismatch returns <b>404, not 401</b>, deliberately: 401 confirms
 *       that something is there to authenticate against, which tells a prober exactly where to
 *       aim. 404 is indistinguishable from the release build's genuine absence, so probing this
 *       path teaches an attacker nothing either way. When the variable is unset the endpoint
 *       refuses everything — an unset secret must fail closed, because a debug endpoint that
 *       silently opens itself when misconfigured is worse than one that never existed.</li>
 *
 *   <li><b>Scope.</b> Only rides that actually exist, and only ride-lifecycle event types.
 *       Anything else is a 400. This is what stops a valid token being a skeleton key: even a
 *       legitimate holder cannot invent a ride, cannot forge a transport-level notice like
 *       {@code CONNECTED}, and cannot fabricate a phase for a rider who has no ride at all.</li>
 * </ol>
 *
 * <p>Every call is logged with the caller's IP and the ride it targeted, accepted or not. A
 * rejected call is the more interesting log line of the two.
 */
@RestController
@CrossOrigin(originPatterns = "*")
@Profile("dev")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    private final RideSessionManager sessions;
    private final RideService rides;

    /**
     * The shared secret, from the {@code CABEYE_DEBUG_TOKEN} environment variable.
     *
     * <p>Defaults to empty, and empty means "reject everything". See the class javadoc: failing
     * closed on a missing secret is the only safe default for an endpoint that can put words in
     * a blind user's ear.
     */
    private final String expectedToken;

    public DebugController(RideSessionManager sessions,
                           RideService rides,
                           @Value("${cabeye.debug.token:${CABEYE_DEBUG_TOKEN:}}") String expectedToken) {
        this.sessions = sessions;
        this.rides = rides;
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();

        if (this.expectedToken.isEmpty()) {
            log.warn("/debug/broadcast is registered but CABEYE_DEBUG_TOKEN is unset — " +
                    "every call will be refused with 404 until it is set.");
        } else {
            log.info("/debug/broadcast is registered (dev profile) and token-protected.");
        }
    }

    /**
     * Pushes a ride-lifecycle event onto an existing ride's topic.
     *
     * <pre>
     * curl -X POST "http://localhost:8080/debug/broadcast?rideId=ride-1001&amp;type=DRIVER_ARRIVED" \
     *      -H "X-Debug-Token: $CABEYE_DEBUG_TOKEN" \
     *      -H "Content-Type: application/json" -d "{}"
     * </pre>
     *
     * @return 404 when the token is wrong or unset, or when the ride does not exist;
     *         400 when the event type is not a ride-lifecycle type;
     *         200 with the delivery count otherwise
     */
    @PostMapping("/debug/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            @RequestParam(required = false) String rideId,
            @RequestParam(required = false) String type,
            @RequestHeader(value = "X-Debug-Token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {

        String caller = callerIp(request);

        // ---- Layer 2: shared secret ---------------------------------------------------
        // Constant-time comparison. The timing signal from String.equals on a short token is
        // small, but it costs nothing to remove and the alternative is arguing about how small.
        if (expectedToken.isEmpty() || !constantTimeEquals(expectedToken, token)) {
            log.warn("DEBUG_BROADCAST REJECTED reason=bad_token ip={} ride={} type={}",
                    caller, rideId, type);
            // 404, not 401 — see the class javadoc.
            return ResponseEntity.notFound().build();
        }

        // ---- Layer 3a: the ride must genuinely exist ----------------------------------
        if (!rides.exists(rideId)) {
            log.warn("DEBUG_BROADCAST REJECTED reason=no_such_ride ip={} ride={} type={}",
                    caller, rideId, type);
            return ResponseEntity.status(404).body(Map.of(
                    "error", "no_such_ride",
                    "rideId", String.valueOf(rideId)
            ));
        }

        // ---- Layer 3b: only ride-lifecycle event types --------------------------------
        if (!RideEventType.isLifecycleType(type)) {
            log.warn("DEBUG_BROADCAST REJECTED reason=bad_type ip={} ride={} type={}",
                    caller, rideId, type);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unsupported_event_type",
                    "type", String.valueOf(type),
                    "allowed", java.util.Arrays.stream(RideEventType.values())
                            .map(Enum::name).toList()
            ));
        }

        // Recorded against the ride's log, not merely fired at the socket, so a client that
        // reconnects after this event still sees it during replay. A debug event that vanishes
        // on reconnect would make the endpoint useless for testing the very thing it exists
        // to test.
        Ride ride = rides.find(rideId).orElseThrow();
        RideEvent event = ride.append(RideEvent.now(
                type, rideId, "debug", "SYSTEM", payload == null ? Map.of() : payload));

        int delivered = sessions.broadcast(rideId, event);

        log.info("DEBUG_BROADCAST ACCEPTED ip={} ride={} type={} seq={} delivered={}",
                caller, rideId, type, event.seq(), delivered);

        return ResponseEntity.ok(Map.of(
                "rideId", rideId,
                "type", type,
                "eventId", event.eventId(),
                "seq", event.seq(),
                "delivered", delivered
        ));
    }

    // -----------------------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------------------

    /**
     * The caller's IP, preferring {@code X-Forwarded-For}.
     *
     * <p>ngrok is in the path during a demo, so without this every call would be logged as
     * 127.0.0.1 and the log would be unable to distinguish a laptop from the open internet —
     * which is exactly the distinction the log exists to record.
     */
    private static String callerIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // May be a comma-separated chain; the left-most entry is the original client.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Length-independent comparison that does not return early on the first differing byte. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
