package com.cabeye.backend.controller;

import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.camera.RideCameraService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The live camera's control plane. The pictures themselves travel over the ride WebSocket;
 * everything that decides whether they may travel at all happens here, over REST, so each
 * side gets a clear yes or no.
 *
 * <pre>
 *   POST /rides/{id}/camera/request   driver asks to see       → {"state":"REQUESTED"}
 *   POST /rides/{id}/camera/answer    rider {"accept":true}     → {"state":"LIVE"}
 *   POST /rides/{id}/camera/stop      either {"reason":"..."}   → {"state":"OFF"}
 *   GET  /rides/{id}/camera           current state
 * </pre>
 *
 * Identity is the sign-in token when there is one, else {@code X-User-Id} — the same rule as
 * the rest of the ride endpoints. Refusals carry a sentence that can be shown or spoken.
 */
@RestController
@CrossOrigin(originPatterns = "*")
public class CameraController {

    private final RideCameraService camera;

    public CameraController(RideCameraService camera) {
        this.camera = camera;
    }

    @GetMapping("/rides/{rideId}/camera")
    public ResponseEntity<Map<String, Object>> state(@PathVariable String rideId) {
        return ResponseEntity.ok(Map.of("state", camera.state(rideId).name()));
    }

    @PostMapping("/rides/{rideId}/camera/request")
    public ResponseEntity<Map<String, Object>> request(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String headerId,
            HttpServletRequest http) {
        return respond(camera.request(rideId, CurrentAccount.idOr(http, headerId)));
    }

    @PostMapping("/rides/{rideId}/camera/answer")
    public ResponseEntity<Map<String, Object>> answer(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "rider-1") String headerId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest http) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean accept = Boolean.TRUE.equals(b.get("accept"));
        String reason = b.get("reason") == null ? "" : String.valueOf(b.get("reason"));
        return respond(camera.answer(rideId, CurrentAccount.idOr(http, headerId), accept, reason));
    }

    @PostMapping("/rides/{rideId}/camera/stop")
    public ResponseEntity<Map<String, Object>> stop(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "anonymous") String headerId,
            @RequestHeader(value = "X-Role", required = false, defaultValue = "RIDER") String role,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest http) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String reason = b.get("reason") == null ? "" : String.valueOf(b.get("reason"));
        String actorRole = "DRIVER".equalsIgnoreCase(role) ? "DRIVER" : "RIDER";
        return respond(camera.stop(rideId, CurrentAccount.idOr(http, headerId), actorRole, reason));
    }

    private static ResponseEntity<Map<String, Object>> respond(RideCameraService.Result result) {
        return switch (result.outcome()) {
            case OK -> ResponseEntity.ok(Map.of("state", result.state().name()));
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", result.message()));
            case FORBIDDEN -> ResponseEntity.status(403).body(Map.of("error", result.message()));
            case CONFLICT -> ResponseEntity.status(409).body(Map.of("error", result.message()));
        };
    }
}
