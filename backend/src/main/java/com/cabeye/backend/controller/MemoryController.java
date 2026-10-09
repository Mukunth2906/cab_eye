package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.memory.MemoryService;
import com.cabeye.backend.memory.SavedRoute;
import com.cabeye.backend.memory.SimulatedTrip;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The signed-in rider's memory.
 *
 * <pre>
 *   GET    /me/memory                     → {"places":[…], "trips":[…], "stats":{…}}
 *   POST   /me/memory/outcome             {"placeKey":"…","kind":"PROACTIVE|REPAIR","accepted":true,"heard":"piece g"}
 *   DELETE /me/memory                     "forget my history"
 *   DELETE /me/memory/places?key=…        forget one place
 * </pre>
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/me/memory")
public class MemoryController {

    private final MemoryService memory;

    private final boolean simulatorEnabled;

    public MemoryController(MemoryService memory,
                            @Value("${cabeye.dev.memory-simulator:false}") boolean simulatorEnabled) {
        this.memory = memory;
        this.simulatorEnabled = simulatorEnabled;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "100") int trips) {
        Account a = CurrentAccount.of(request).orElseThrow();
        Map<String, Object> out = new HashMap<>();
        out.put("places", memory.places(a.id));
        out.put("trips", memory.trips(a.id, Math.max(1, Math.min(trips, 300))));
        out.put("stats", memory.stats(a.id));
        out.put("routes", memory.savedRoutes(a.id));
        return ResponseEntity.ok(out);
    }

    /** "Save this as Monday errands." Body: a {@link SavedRoute} (name, stops, destination, rideType). */
    @PostMapping("/routes")
    public ResponseEntity<?> saveRoute(HttpServletRequest request, @RequestBody SavedRoute body) {
        Account a = CurrentAccount.of(request).orElseThrow();
        try {
            return ResponseEntity.ok(memory.saveRoute(a.id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** The rider booked a saved route by name. */
    @PostMapping("/routes/used")
    public ResponseEntity<Map<String, Object>> routeUsed(HttpServletRequest request, @RequestParam("name") String name) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(Map.of("found", memory.routeUsed(a.id, name).isPresent()));
    }

    @DeleteMapping("/routes")
    public ResponseEntity<Map<String, Object>> forgetRoute(HttpServletRequest request, @RequestParam("name") String name) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(Map.of("forgotten", memory.forgetRoute(a.id, name)));
    }

    /**
     * Dev only — "be the user": feeds a described ride history into this rider's memory, so
     * the phone's suggestions can be demonstrated without weeks of real rides. Off unless
     * {@code cabeye.dev.memory-simulator=true}; answers 404 otherwise, like any unknown path.
     * Body: {@code {"trips":[{"at":…,"destination":{…},"stops":[{…,"kind":"WAIT"}]}]}}.
     */
    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(HttpServletRequest request, @RequestBody SimulateRequest body) {
        if (!simulatorEnabled) return ResponseEntity.notFound().build();
        Account a = CurrentAccount.of(request).orElseThrow();
        try {
            return ResponseEntity.ok(Map.of("recorded", memory.simulate(a.id, body == null ? null : body.trips)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public static class SimulateRequest {
        public java.util.List<SimulatedTrip> trips;
    }

    @PostMapping("/outcome")
    public ResponseEntity<Map<String, Object>> outcome(HttpServletRequest request,
                                                       @RequestBody Map<String, Object> body) {
        Account a = CurrentAccount.of(request).orElseThrow();
        String key = body.get("placeKey") == null ? "" : String.valueOf(body.get("placeKey"));
        String kind = body.get("kind") == null ? "PROACTIVE" : String.valueOf(body.get("kind"));
        boolean accepted = Boolean.parseBoolean(String.valueOf(body.get("accepted")));
        String heard = body.get("heard") == null ? "" : String.valueOf(body.get("heard"));

        Map<String, Object> out = new HashMap<>();
        memory.recordOutcome(a.id, key, kind, accepted, heard).ifPresent(p -> out.put("place", p));
        out.put("stats", memory.stats(a.id));
        return ResponseEntity.ok(out);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> forgetAll(HttpServletRequest request) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(Map.of("forgotten", memory.forgetAll(a.id)));
    }

    @DeleteMapping("/places")
    public ResponseEntity<Map<String, Object>> forgetPlace(HttpServletRequest request,
                                                           @RequestParam("key") String key) {
        Account a = CurrentAccount.of(request).orElseThrow();
        return ResponseEntity.ok(Map.of("forgotten", memory.forgetPlace(a.id, key)));
    }
}
