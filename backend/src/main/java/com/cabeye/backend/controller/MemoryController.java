package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.memory.MemoryService;
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

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "100") int trips) {
        Account a = CurrentAccount.of(request).orElseThrow();
        Map<String, Object> out = new HashMap<>();
        out.put("places", memory.places(a.id));
        out.put("trips", memory.trips(a.id, Math.max(1, Math.min(trips, 300))));
        out.put("stats", memory.stats(a.id));
        return ResponseEntity.ok(out);
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
