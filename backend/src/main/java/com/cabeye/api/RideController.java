package com.cabeye.api;

import com.cabeye.api.dto.InterpretResponse.PlaceDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ride, barely.
 *
 * <p>This is a placeholder and is marked as one. Matching, pricing,
 * driver state and the WebSocket that carries live position all belong
 * here eventually; none of them exist yet, because the prototype's two
 * apps still talk to each other over a {@code BroadcastChannel} in the
 * browser and putting a server in the middle would slow the demo down
 * without testing anything new.
 *
 * <p>What it does give is a stable URL shape to build against, and a
 * ride id the logs can be correlated by once there is more than one
 * process involved. Rides live in a map and die with the process.
 */
@RestController
@RequestMapping("/api/v1/rides")
public class RideController {

    private final Map<String, Map<String, Object>> rides = new ConcurrentHashMap<>();

    @PostMapping
    public Map<String, Object> request(@RequestBody RideRequest req) {
        String id = UUID.randomUUID().toString();

        // Fare and ETA are invented, exactly as they are in the browser
        // prototype. Kept on the server only so both apps agree on the
        // same made-up number once they stop sharing a page.
        int fare = 120 + (int) (Math.random() * 140);
        int etaMinutes = 4 + (int) (Math.random() * 7);

        Map<String, Object> ride = Map.of(
                "id", id,
                "status", "SEARCHING",
                "destination", req.destination(),
                "rideType", req.rideType() == null ? "auto" : req.rideType(),
                "fare", fare,
                "etaMinutes", etaMinutes,
                "requestedAt", Instant.now().toString()
        );
        rides.put(id, ride);
        return ride;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Map<String, Object> ride = rides.get(id);
        return ride == null ? Map.of("id", id, "status", "UNKNOWN") : ride;
    }

    public record RideRequest(PlaceDto destination, String rideType, String riderId) { }
}
