package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideStop;
import com.cabeye.backend.service.RideService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Multi-stop rides — the commands that move a ride through its stops.
 *
 * <pre>
 *   POST /rides/{rideId}/stops/{stopId}/arrived   driver reached the stop
 *   POST /rides/{rideId}/stops/{stopId}/done      driver finished it (WAIT: only once the rider is back)
 *   POST /rides/{rideId}/stops/{stopId}/skip      rider drops a stop
 *   PUT  /rides/{rideId}/stops   {"stops":[...]}  rider replaces the stops still ahead (add / remove / reorder)
 * </pre>
 *
 * A WAIT stop's "rider is back" is the ordinary {@code POST /rides/{id}/code}: the rider's phone
 * heard the boarding code again. Refusals come back as {@code {"error": "<sentence>"}} so the
 * rider's phone can say it and the driver's screen can show it.
 */
@RestController
@CrossOrigin(originPatterns = "*")
public class StopController {

    private final RideService rides;

    public StopController(RideService rides) {
        this.rides = rides;
    }

    @PostMapping("/rides/{rideId}/stops/{stopId}/arrived")
    public ResponseEntity<?> arrived(@PathVariable String rideId, @PathVariable String stopId,
                                     @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String header,
                                     HttpServletRequest request) {
        return run(rideId, request, true, () -> rides.stopArrived(rideId, CurrentAccount.idOr(request, header), stopId));
    }

    @PostMapping("/rides/{rideId}/stops/{stopId}/done")
    public ResponseEntity<?> done(@PathVariable String rideId, @PathVariable String stopId,
                                  @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String header,
                                  HttpServletRequest request) {
        return run(rideId, request, true, () -> rides.stopDone(rideId, CurrentAccount.idOr(request, header), stopId));
    }

    @PostMapping("/rides/{rideId}/stops/{stopId}/skip")
    public ResponseEntity<?> skip(@PathVariable String rideId, @PathVariable String stopId,
                                  @RequestHeader(value = "X-User-Id", required = false, defaultValue = "rider-1") String header,
                                  HttpServletRequest request) {
        return run(rideId, request, false, () -> rides.skipStop(rideId, CurrentAccount.idOr(request, header), stopId));
    }

    @PutMapping("/rides/{rideId}/stops")
    public ResponseEntity<?> replace(@PathVariable String rideId,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @RequestHeader(value = "X-User-Id", required = false, defaultValue = "rider-1") String header,
                                     HttpServletRequest request) {
        List<RideStop> upcoming = parseStops(body == null ? null : body.get("stops"));
        return run(rideId, request, false,
                () -> rides.replaceUpcomingStops(rideId, CurrentAccount.idOr(request, header), upcoming));
    }

    // -----------------------------------------------------------------------------------

    /**
     * Runs a stop command. When the caller is signed in, only this ride's own driver (or rider)
     * may move it — the stop list decides where a blind passenger is let out of the car.
     */
    private ResponseEntity<?> run(String rideId, HttpServletRequest request, boolean driverAction,
                                  Supplier<Ride> action) {
        Account caller = CurrentAccount.of(request).orElse(null);
        if (caller != null) {
            Ride ride = rides.find(rideId).orElse(null);
            if (ride != null) {
                String owner = driverAction ? ride.driverId() : ride.riderId();
                if (owner != null && !owner.equals(caller.id)) {
                    return ResponseEntity.status(403).body(Map.of("error",
                            driverAction ? "Only this ride's driver can do that." : "That ride isn't yours."));
                }
            }
        }
        try {
            return ResponseEntity.ok(action.get().snapshot());
        } catch (RideService.StopRefused e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }

    /** Lenient parse of a JSON stop list: unknown keys ignored, kind defaults to DROP. */
    @SuppressWarnings("unchecked")
    public static List<RideStop> parseStops(Object raw) {
        List<RideStop> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m0)) continue;
            Map<String, Object> m = (Map<String, Object>) m0;
            RideStop s = new RideStop();
            s.stopId = str(m.get("stopId"));
            s.kind = RideStop.Kind.parse(m.get("kind"));
            s.name = str(m.get("name"));
            s.address = str(m.get("address"));
            s.latitude = decimal(m.get("latitude"));
            s.longitude = decimal(m.get("longitude"));
            s.placeId = str(m.get("placeId"));
            s.spokenAs = str(m.get("spokenAs"));
            s.note = str(m.get("note"));
            out.add(s);
        }
        return out;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Double decimal(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
