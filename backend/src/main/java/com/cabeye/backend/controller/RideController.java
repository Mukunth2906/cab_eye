package com.cabeye.backend.controller;

import com.cabeye.backend.account.Account;
import com.cabeye.backend.auth.CurrentAccount;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.service.RideService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The ride REST surface.
 *
 * <p>Two different jobs live here and it is worth being explicit about which is which:
 *
 * <ul>
 *   <li><b>Commands</b> (everything under {@code POST}) are how the driver app drives the ride
 *       forward. They could have gone over the WebSocket, but REST gives each one an
 *       unambiguous success or failure the caller can act on — a driver tapping "confirm
 *       seated" needs to know it took.</li>
 *   <li><b>{@code GET /rides/{id}}</b> is the reconciliation endpoint, and it is the reason
 *       this controller exists at all. After a dropped socket the rider's app fetches this and
 *       announces only what changed. Without it the app's only options are to replay a backlog
 *       at a rider who has moved on, or to stay silent — and silence is reserved for
 *       "everything is fine".</li>
 * </ul>
 *
 * <p>Identity: when the request carries a valid sign-in token, the account behind it is the
 * rider who books or the driver who accepts, and {@code X-User-Id} is ignored. Without a token
 * the header is still honoured, so the browser test page and the older tests keep working.
 */
@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/rides")
public class RideController {

    private static final Logger log = LoggerFactory.getLogger(RideController.class);

    private final RideService rides;

    public RideController(RideService rides) {
        this.rides = rides;
    }

    // ===================================================================================
    //  Rider
    // ===================================================================================

    /**
     * Books a ride.
     *
     * <p>Body: {@code {"destination":"Anna Nagar East","rideType":"AUTO"}}
     *
     * @return the full snapshot, including the {@code boardingCode} the rider's app will later
     *   verify against what it hears the driver say
     */
    @PostMapping
    public ResponseEntity<Ride.Snapshot> create(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "rider-1") String headerRiderId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        String riderId = CurrentAccount.idOr(request, headerRiderId);

        String destination = str(body.get("destination"), "Unknown");
        String destinationAddress = str(body.get("destinationAddress"), "");
        Double destinationLatitude = decimal(body.get("destinationLatitude"));
        Double destinationLongitude = decimal(body.get("destinationLongitude"));
        String destinationPlaceId = str(body.get("destinationPlaceId"), "");
        Double pickupLatitude = decimal(body.get("pickupLatitude"));
        Double pickupLongitude = decimal(body.get("pickupLongitude"));
        // Optional meeting contact. Absent on the ordinary ride to a building; present only
        // when the destination was vague enough that the rider named someone waiting there.
        String contactName = str(body.get("contactName"), "");
        String contactPhone = str(body.get("contactPhone"), "");
        String dropNote = str(body.get("dropNote"), "");
        String rideType = str(body.get("rideType"), "AUTO");

        Ride ride = rides.create(riderId, destination, destinationAddress,
                destinationLatitude, destinationLongitude, destinationPlaceId,
                pickupLatitude, pickupLongitude, contactName, contactPhone, dropNote, rideType);
        // What the rider said, for their memory. Optional; older clients never send it.
        ride.spokenAs(str(body.get("spokenAs"), ""));
        return ResponseEntity.ok(ride.snapshot());
    }

    /**
     * The reconciliation endpoint.
     *
     * <p>Called on every reconnect, before the client trusts a single replayed event. The
     * client compares this phase against the last thing it told the rider out loud and speaks
     * the difference — one sentence, not a backlog.
     */
    @GetMapping("/{rideId}")
    public ResponseEntity<Ride.Snapshot> get(@PathVariable String rideId) {
        return rides.find(rideId)
                .map(ride -> ResponseEntity.ok(ride.snapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The retained event log, for a client that wants the delta rather than the snapshot.
     *
     * <p>Every event keeps its original {@code eventId}, so a client which already handled one
     * discards it rather than acting twice.
     */
    @GetMapping("/{rideId}/events")
    public ResponseEntity<List<RideEvent>> events(@PathVariable String rideId,
                                                  @RequestParam(defaultValue = "0") long afterSeq) {
        return rides.find(rideId)
                .map(ride -> ResponseEntity.ok(ride.eventsAfter(afterSeq)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The rider's app reporting whether the code it heard matched the one it was told. */
    @PostMapping("/{rideId}/code")
    public ResponseEntity<Ride.Snapshot> confirmCode(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "rider-1") String riderId,
            @RequestBody(required = false) Map<String, Object> body) {

        boolean matched = body == null || !Boolean.FALSE.equals(body.get("matched"));
        return respond(rides.confirmCode(rideId, riderId, matched));
    }

    // ===================================================================================
    //  Payment
    // ===================================================================================

    /**
     * The rider's app relaying what its UPI app claimed.
     *
     * <p>Stored as REPORTED, never CONFIRMED — see {@link RideService#reportPayment}.
     */
    @PostMapping("/{rideId}/payment")
    public ResponseEntity<Ride.Snapshot> reportPayment(
            @PathVariable String rideId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> b = body == null ? Map.of() : body;
        return respond(rides.reportPayment(
                rideId,
                str(b.get("status"), "SUBMITTED"),
                str(b.get("txnRef"), "")));
    }

    /** Polled by the rider's app until the payment status settles, so it can speak the result. */
    @GetMapping("/{rideId}/payment")
    public ResponseEntity<Ride.Snapshot> paymentStatus(@PathVariable String rideId) {
        return rides.find(rideId)
                .map(ride -> ResponseEntity.ok(ride.snapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Stands in for the payment provider webhook that does not exist on the deep-link path.
     *
     * <p>Kept as an explicit endpoint so the CONFIRMED transition is demonstrable and so it is
     * obvious in the code that nothing produces it automatically. A real integration replaces
     * this with a signed webhook from the provider.
     */
    @PostMapping("/{rideId}/payment/confirm")
    public ResponseEntity<Ride.Snapshot> confirmPayment(@PathVariable String rideId) {
        return respond(rides.confirmPayment(rideId));
    }

    // ===================================================================================
    //  Driver
    // ===================================================================================

    /** Ride requests a driver may accept. Polled once when the driver goes online. */
    @GetMapping("/open")
    public List<Ride.Snapshot> open() {
        return rides.openRequests().stream().map(Ride::snapshot).toList();
    }

    /**
     * Accept a request.
     *
     * <p>Body: {@code {"driverName":"Karthik","vehicleModel":"…","vehiclePlate":"…",
     * "driverPhone":"…","etaMinutes":4}}
     *
     * @return 409 when another driver took it first — a real outcome, not an error
     */
    @PostMapping("/{rideId}/accept")
    public ResponseEntity<?> accept(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String headerDriverId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        Map<String, Object> b = body == null ? Map.of() : body;
        // A signed-in driver's profile is the source of truth for what the rider is told to
        // look for. The body is only a fallback for the unauthenticated test page.
        Account driver = CurrentAccount.of(request).orElse(null);
        Account.DriverProfile profile = driver == null ? null : driver.driver;
        String driverId = driver == null ? headerDriverId : driver.id;

        if (profile != null && profile.suspended) {
            log.warn("ACCEPT_REFUSED ride={} driver={} (suspended by admin)", rideId, driverId);
            return ResponseEntity.status(403).body(Map.of("error",
                    "Your account is paused by Cab Eye support, so you can't take rides right now. Please contact support."));
        }

        return rides.assign(
                        rideId,
                        driverId,
                        firstNonBlank(driver == null ? null : driver.name, str(b.get("driverName"), "Driver")),
                        firstNonBlank(vehicleDescription(profile), str(b.get("vehicleModel"), "Auto")),
                        firstNonBlank(profile == null ? null : profile.vehiclePlate, str(b.get("vehiclePlate"), "TN 00 AA 0000")),
                        firstNonBlank(driver == null ? null : driver.phone, str(b.get("driverPhone"), "")),
                        num(b.get("etaMinutes"), 4))
                .<ResponseEntity<?>>map(ride -> ResponseEntity.ok(ride.snapshot()))
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    /** "white Bajaj RE" — colour first, because that is what a helper on the street looks for. */
    private static String vehicleDescription(Account.DriverProfile p) {
        if (p == null || p.vehicleModel == null || p.vehicleModel.isBlank()) return null;
        return (p.vehicleColour == null || p.vehicleColour.isBlank())
                ? p.vehicleModel
                : p.vehicleColour + " " + p.vehicleModel;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /** Driver started navigating to the pickup point. */
    @PostMapping("/{rideId}/enroute")
    public ResponseEntity<Ride.Snapshot> enroute(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId) {
        return respond(rides.enroute(rideId, driverId));
    }

    /**
     * A position update. Body: {@code {"distanceMeters":120,"bearingDeg":45}}
     *
     * <p>Tier 2 on the rider's phone: an earcon whose pitch carries the distance and whose pan
     * carries the bearing, and not one spoken word.
     */
    @PostMapping("/{rideId}/location")
    public ResponseEntity<Ride.Snapshot> location(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId,
            @RequestBody Map<String, Object> body) {
        return respond(rides.location(rideId, driverId,
                num(body.get("distanceMeters"), 0),
                (float) num(body.get("bearingDeg"), 0)));
    }

    /**
     * A canned position phrase. Body: {@code {"text":"I'm twenty metres to your left…"}}
     *
     * <p>The driver taps it; the rider's phone speaks it. Neither one crosses into the other's
     * modality, which is the point — a driver reading text aloud while driving is a hazard, and
     * a blind rider cannot read anything at all.
     */
    @PostMapping("/{rideId}/preset")
    public ResponseEntity<Ride.Snapshot> preset(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId,
            @RequestBody Map<String, Object> body) {
        return respond(rides.positionPreset(rideId, driverId, str(body.get("text"), "")));
    }

    /** Audio beacon. Body: {@code {"bearingDeg":45}} */
    @PostMapping("/{rideId}/beacon")
    public ResponseEntity<Ride.Snapshot> beacon(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return respond(rides.beacon(rideId, driverId, (float) num(b.get("bearingDeg"), 0)));
    }

    /** Driver is at the pickup point. The response carries the code for the driver to display. */
    @PostMapping("/{rideId}/arrived")
    public ResponseEntity<Ride.Snapshot> arrived(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId) {
        return respond(rides.arrived(rideId, driverId));
    }

    /** Driver confirms the passenger is physically in the vehicle. Gates {@link #start}. */
    @PostMapping("/{rideId}/seated")
    public ResponseEntity<?> seated(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId) {
        var ride = rides.find(rideId).orElse(null);
        if (ride != null && !ride.phase().isTerminal() && !ride.codeConfirmed()) {
            // 409 with the sentence the driver sees: say the code, near the passenger's phone.
            return ResponseEntity.status(409).body(Map.of("error",
                    "Your passenger's phone hasn't confirmed the code yet. Say the code clearly, close to their phone."));
        }
        return respond(rides.passengerSeated(rideId, driverId));
    }

    /**
     * Start the journey.
     *
     * @return 409 unless the passenger has been confirmed seated. This is the server refusing,
     *   not the driver app choosing to — a UI can be worked around, a state machine cannot.
     */
    @PostMapping("/{rideId}/start")
    public ResponseEntity<Ride.Snapshot> start(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String driverId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> b = body == null ? Map.of() : body;
        return rides.startTrip(rideId, driverId, num(b.get("etaMinutes"), 12))
                .map(ride -> ResponseEntity.ok(ride.snapshot()))
                .orElseGet(() -> {
                    log.warn("START refused for ride={} — passenger not confirmed seated", rideId);
                    return ResponseEntity.status(409).build();
                });
    }

    /**
     * One GPS fix from the driver's phone during the trip. Body: {@code {"lat":11.02,"lng":76.96}}
     *
     * <p>Optional {@code "at"}: when the phone took the fix (epoch ms, from its GPS), so fixes
     * sent together after a signal gap are still timed correctly.
     *
     * <p>The server adds up the distance (ignoring GPS jitter and glitches) and publishes
     * TRIP_PROGRESS with the km and live fare. 409 when the ride is not in a trip. A signed-in
     * account that is not this ride's driver is refused, so nobody else can move the meter.
     */
    @PostMapping("/{rideId}/trip-location")
    public ResponseEntity<?> tripLocation(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String headerDriverId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Double lat = decimal(b.get("lat"));
        Double lng = decimal(b.get("lng"));
        if (lat == null || lng == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A position needs lat and lng."));
        }
        Ride ride = rides.find(rideId).orElse(null);
        if (ride == null) {
            return ResponseEntity.notFound().build();
        }
        Account signedIn = CurrentAccount.of(request).orElse(null);
        if (signedIn != null && ride.driverId() != null && !ride.driverId().equals(signedIn.id)) {
            log.warn("TRIP_LOCATION_REFUSED ride={} by={} (not this ride's driver)", rideId, signedIn.id);
            return ResponseEntity.status(403).body(Map.of("error", "That isn't your ride."));
        }
        String driverId = signedIn == null ? headerDriverId : signedIn.id;
        Object at = b.get("at");
        Long atMillis = at instanceof Number n ? Long.valueOf(n.longValue()) : null;
        return rides.tripLocation(rideId, driverId, lat, lng, atMillis)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(r.snapshot()))
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "The trip hasn't started.")));
    }

    /**
     * Journey finished. The server prices it from the measured distance and time; any
     * {@code fareRupees} / {@code durationMinutes} in the body (sent by older app builds) is
     * ignored, because a fare must never be whatever a phone says it is.
     */
    @PostMapping("/{rideId}/complete")
    public ResponseEntity<Ride.Snapshot> complete(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "driver-1") String headerDriverId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        if (body != null && body.containsKey("fareRupees")) {
            log.info("COMPLETE ride={} ignoring client fare {} (fare is computed on the server)",
                    rideId, body.get("fareRupees"));
        }
        return respond(rides.completeTrip(rideId, CurrentAccount.idOr(request, headerDriverId)));
    }

    /** Cancel, from either side. */
    @PostMapping("/{rideId}/cancel")
    public ResponseEntity<Ride.Snapshot> cancel(
            @PathVariable String rideId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "anonymous") String userId,
            @RequestHeader(value = "X-Role", required = false, defaultValue = "RIDER") String role,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return respond(rides.cancel(rideId, userId, role.toUpperCase(), str(b.get("reason"), "")));
    }

    // ===================================================================================
    //  Helpers
    // ===================================================================================

    private ResponseEntity<Ride.Snapshot> respond(java.util.Optional<Ride> ride) {
        return ride.map(r -> ResponseEntity.ok(r.snapshot()))
                   .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String str(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static Double decimal(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int num(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return fallback;
        try {
            return (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
