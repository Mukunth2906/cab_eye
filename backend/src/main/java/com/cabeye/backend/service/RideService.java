package com.cabeye.backend.service;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.model.RideEventType;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.websocket.RideSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The ride state machine, and the only thing allowed to move a ride between phases.
 *
 * <p>Every transition does exactly two things, in this order and never in the other:
 * <ol>
 *   <li>mutate the ride and append the event to its log (so the evidence exists), then</li>
 *   <li>broadcast it (so clients learn about it).</li>
 * </ol>
 * A reconnecting client reconciles against the phase, so a phase that had moved without its
 * event being recorded would be a state the client could see but never explain.
 *
 * <h2>The dispatch topic</h2>
 * Drivers who are online but not yet on a ride join a reserved pseudo-ride, {@link #DISPATCH_TOPIC}.
 * It gives drivers somewhere to listen before there is a ride to listen to, without inventing a
 * second socket endpoint. It is not a {@link Ride} and never appears in {@link #all()}, so it
 * cannot be a debug-broadcast target.
 */
@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    /** Reserved topic name that online drivers subscribe to for ride offers. */
    public static final String DISPATCH_TOPIC = "dispatch";

    private final RideSessionManager sessions;

    private final Map<String, Ride> rides = new ConcurrentHashMap<>();
    private final AtomicLong rideCounter = new AtomicLong(1000);
    private final SecureRandom random = new SecureRandom();

    /**
     * Called once when a ride reaches COMPLETED — driver stats and the rider's trip memory hang
     * off this. Listeners register themselves, so this class never learns about accounts or
     * memory and its constructor stays the one the tests already use.
     */
    private final List<Consumer<Ride>> completionListeners = new CopyOnWriteArrayList<>();

    public RideService(RideSessionManager sessions) {
        this.sessions = sessions;
    }

    public void onCompleted(Consumer<Ride> listener) {
        completionListeners.add(listener);
    }

    /**
     * Every event this service publishes on a ride, after it has been recorded and broadcast.
     * The live camera hangs off this so it can switch itself off the moment the boarding code
     * is confirmed, the passenger is seated or the ride ends — without this class knowing the
     * camera exists.
     */
    private final List<java.util.function.BiConsumer<Ride, RideEvent>> eventListeners = new CopyOnWriteArrayList<>();

    public void onEvent(java.util.function.BiConsumer<Ride, RideEvent> listener) {
        eventListeners.add(listener);
    }

    /** Called once for every new ride, right after it is created — used to save it. */
    private final List<Consumer<Ride>> createdListeners = new CopyOnWriteArrayList<>();

    public void onCreated(Consumer<Ride> listener) {
        createdListeners.add(listener);
    }

    /**
     * Puts back a ride saved before a restart. Ride numbering continues after the highest
     * restored id, so a new ride can never reuse the id of one already in the database.
     */
    public synchronized void restore(Ride ride) {
        rides.put(ride.rideId(), ride);
        String id = ride.rideId();
        if (id != null && id.startsWith("ride-")) {
            try {
                long n = Long.parseLong(id.substring(5));
                rideCounter.accumulateAndGet(n, Math::max);
            } catch (NumberFormatException ignored) {
                // Not one of ours; leave the counter alone.
            }
        }
    }

    // ===================================================================================
    //  Lookup
    // ===================================================================================

    public Optional<Ride> find(String rideId) {
        return Optional.ofNullable(rides.get(rideId));
    }

    /** @return true when this ride genuinely exists — the scope check for the debug endpoint. */
    public boolean exists(String rideId) {
        return rideId != null && rides.containsKey(rideId);
    }

    public Collection<Ride> all() {
        return rides.values();
    }

    /** Rides a driver may accept: requested, unassigned, not terminal. */
    public List<Ride> openRequests() {
        List<Ride> out = new ArrayList<>();
        for (Ride ride : rides.values()) {
            if (ride.phase() == RidePhase.REQUESTED && ride.driverId() == null) {
                out.add(ride);
            }
        }
        return out;
    }

    // ===================================================================================
    //  Transitions
    // ===================================================================================

    /**
     * Creates a ride and offers it to every online driver.
     *
     * <p>The boarding code is generated <b>here</b>, server-side, and never by either phone.
     * The rider's app is told the code so it can verify what it hears; the driver's app is told
     * the code so it can display it. Neither one invents it, which is what makes the code
     * evidence rather than a shared guess.
     */
    public Ride create(String riderId, String destination, String rideType) {
        return create(riderId, destination, "", null, null, "", null, null, rideType);
    }

    public Ride create(String riderId,
                       String destination,
                       String destinationAddress,
                       Double destinationLatitude,
                       Double destinationLongitude,
                       String destinationPlaceId,
                       Double pickupLatitude,
                       Double pickupLongitude,
                       String rideType) {
        return create(riderId, destination, destinationAddress, destinationLatitude,
                destinationLongitude, destinationPlaceId, pickupLatitude, pickupLongitude,
                "", "", "", rideType);
    }

    /**
     * Creates a ride that may carry a meeting contact.
     *
     * <p>{@code contactName} and {@code contactPhone} are blank for the ordinary case — a ride
     * to a building needs no one to ring. They are populated only when the destination was
     * vague enough that the rider named someone waiting there.
     */
    public Ride create(String riderId,
                       String destination,
                       String destinationAddress,
                       Double destinationLatitude,
                       Double destinationLongitude,
                       String destinationPlaceId,
                       Double pickupLatitude,
                       Double pickupLongitude,
                       String contactName,
                       String contactPhone,
                       String dropNote,
                       String rideType) {
        String rideId = "ride-" + rideCounter.incrementAndGet();
        String code = generateBoardingCode();

        Ride ride = new Ride(rideId, riderId, destination, destinationAddress,
                destinationLatitude, destinationLongitude, destinationPlaceId,
                pickupLatitude, pickupLongitude, contactName, contactPhone, dropNote, rideType, code);
        rides.put(rideId, ride);

        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", rideId);
        payload.put("riderId", riderId);
        payload.put("destination", destination);
        payload.put("destinationAddress", destinationAddress);
        payload.put("destinationLatitude", destinationLatitude);
        payload.put("destinationLongitude", destinationLongitude);
        payload.put("destinationPlaceId", destinationPlaceId);
        payload.put("pickupLatitude", pickupLatitude);
        payload.put("pickupLongitude", pickupLongitude);
        payload.put("contactName", contactName);
        payload.put("contactPhone", contactPhone);
        payload.put("dropNote", dropNote);
        payload.put("rideType", rideType);

        RideEvent event = record(ride, RideEventType.RIDE_CREATED, "server", "SYSTEM", payload);

        sessions.broadcast(rideId, event);
        // Offer it to the driver pool. Drivers are not on the ride topic yet — they have not
        // accepted anything — so this is a separate fan-out, not a duplicate of the line above.
        sessions.broadcast(DISPATCH_TOPIC, event);

        log.info("RIDE_CREATED ride={} rider={} destination=\"{}\" type={} code={}",
                rideId, riderId, destination, rideType, code);
        for (Consumer<Ride> listener : createdListeners) {
            try {
                listener.accept(ride);
            } catch (RuntimeException e) {
                log.warn("CREATED_LISTENER_FAILED ride={} : {}", rideId, e.toString());
            }
        }
        return ride;
    }

    /** A driver accepted. Returns empty if the ride is gone or somebody else already took it. */
    public synchronized Optional<Ride> assign(String rideId,
                                              String driverId,
                                              String driverName,
                                              String vehicleModel,
                                              String vehiclePlate,
                                              String driverPhone,
                                              int etaMinutes) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase() != RidePhase.REQUESTED || ride.driverId() != null) {
            return Optional.empty();
        }

        ride.assignDriver(driverId, driverName, vehicleModel, vehiclePlate, driverPhone, etaMinutes);
        ride.phase(RidePhase.ASSIGNED);

        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driverId);
        payload.put("driverName", driverName);
        payload.put("vehicleModel", vehicleModel);
        payload.put("vehiclePlate", vehiclePlate);
        payload.put("driverPhone", driverPhone);
        payload.put("etaMinutes", etaMinutes);

        publish(ride, RideEventType.RIDE_ASSIGNED, driverId, "DRIVER", payload);

        // Withdraw the offer from every other online driver.
        sessions.broadcast(DISPATCH_TOPIC, RideEvent.system("REQUEST_TAKEN", rideId,
                Map.of("rideId", rideId, "driverId", driverId)));

        log.info("RIDE_ASSIGNED ride={} driver={} eta={}min", rideId, driverId, etaMinutes);
        return Optional.of(ride);
    }

    /** Driver started navigating to the pickup. */
    public Optional<Ride> enroute(String rideId, String driverId) {
        return transition(rideId, RidePhase.ENROUTE, RideEventType.DRIVER_ENROUTE, driverId,
                Map.of(), RidePhase.ASSIGNED);
    }

    /**
     * A driver position update.
     *
     * <p>Deliberately does <b>not</b> change the phase. It fires many times per ride and is
     * tier 2 on the rider's phone — an earcon whose pitch carries the distance and whose pan
     * carries the bearing. Coupling it to a phase change would make every tone a state
     * transition the reconnect logic would then have to reconcile against.
     */
    public Optional<Ride> location(String rideId, String driverId, int distanceMeters, float bearingDeg) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.distance(distanceMeters);
        ride.bearing(bearingDeg);

        publish(ride, RideEventType.DRIVER_LOCATION, driverId, "DRIVER", Map.of(
                "distanceMeters", distanceMeters,
                "bearingDeg", bearingDeg
        ));
        return Optional.of(ride);
    }

    /**
     * A canned position phrase from the driver, spoken by the rider's phone.
     *
     * <p>The driver never speaks it and the rider never reads it. Text travels; audio is
     * produced at the ear that needs it.
     */
    public Optional<Ride> positionPreset(String rideId, String driverId, String text) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        publish(ride, RideEventType.POSITION_PRESET, driverId, "DRIVER", Map.of("text", text));
        return Optional.of(ride);
    }

    /** Audio beacon, panned on the rider's phone to the bearing given here. */
    public Optional<Ride> beacon(String rideId, String driverId, float bearingDeg) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.bearing(bearingDeg);
        publish(ride, RideEventType.BEACON, driverId, "DRIVER", Map.of("bearingDeg", bearingDeg));
        return Optional.of(ride);
    }

    /**
     * Driver is at the pickup point. Carries the boarding code the driver will read aloud.
     *
     * <p>Tier 0 on the rider's phone, and the only ride event besides deviation and connection
     * loss permitted to interrupt.
     */
    public Optional<Ride> arrived(String rideId, String driverId) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.phase(RidePhase.ARRIVED);
        publish(ride, RideEventType.DRIVER_ARRIVED, driverId, "DRIVER", Map.of(
                "boardingCode", ride.boardingCode()
        ));
        log.info("DRIVER_ARRIVED ride={} code={}", rideId, ride.boardingCode());
        return Optional.of(ride);
    }

    /** The rider's app verified the code it heard the driver say aloud. */
    public Optional<Ride> confirmCode(String rideId, String riderId, boolean matched) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.codeConfirmed(matched);
        publish(ride, RideEventType.CODE_CONFIRMED, riderId, "RIDER", Map.of("matched", matched));
        return Optional.of(ride);
    }

    /**
     * The driver confirmed the passenger is physically in the vehicle.
     *
     * <p>This is a hard gate, not a courtesy: {@link #startTrip} refuses to run until it has
     * happened. A blind passenger who has not finished getting in is the one person who cannot
     * see a car start moving, so "the trip started" must never be able to precede "they are in".
     */
    public Optional<Ride> passengerSeated(String rideId, String driverId) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        // The boarding code is the rider's only proof this is their car, so "seated" cannot
        // skip it. Found in the demo: the driver tapped PASSENGER IS SEATED and the ride moved
        // on with no code ever checked. If the rider's phone cannot hear the driver, the rider
        // (or a helper) confirms with "Code is right" on their own phone — never the driver.
        if (!ride.codeConfirmed()) {
            log.warn("SEATED_REFUSED ride={} phase={} (boarding code not confirmed)", rideId, ride.phase());
            return Optional.empty();
        }
        ride.phase(RidePhase.SEATED);
        publish(ride, RideEventType.PASSENGER_SEATED, driverId, "DRIVER", Map.of());
        return Optional.of(ride);
    }

    /** Starts the journey. Refuses unless the passenger has been confirmed seated. */
    public Optional<Ride> startTrip(String rideId, String driverId, int etaMinutes) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase() != RidePhase.SEATED) {
            log.warn("TRIP_START_REFUSED ride={} phase={} (passenger not confirmed seated)",
                    rideId, ride == null ? "MISSING" : ride.phase());
            return Optional.empty();
        }
        ride.phase(RidePhase.IN_TRIP);
        ride.etaMinutes(etaMinutes);
        publish(ride, RideEventType.TRIP_STARTED, driverId, "DRIVER", Map.of(
                "destination", ride.destination(),
                "etaMinutes", etaMinutes
        ));
        return Optional.of(ride);
    }

    /** Ends the journey. */
    public Optional<Ride> complete(String rideId, String driverId, int fareRupees, int durationMinutes) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.phase(RidePhase.COMPLETED);
        ride.fare(fareRupees);
        ride.durationMinutes(durationMinutes);
        publish(ride, RideEventType.TRIP_COMPLETED, driverId, "DRIVER", Map.of(
                "destination", ride.destination(),
                "fareRupees", fareRupees,
                "durationMinutes", durationMinutes
        ));
        for (Consumer<Ride> listener : completionListeners) {
            // A memory or stats failure must never undo a finished ride.
            try {
                listener.accept(ride);
            } catch (RuntimeException e) {
                log.warn("COMPLETION_LISTENER_FAILED ride={} : {}", rideId, e.toString());
            }
        }
        return Optional.of(ride);
    }

    /** Cancelled by either party. */
    public Optional<Ride> cancel(String rideId, String actorId, String actorRole, String reason) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        ride.phase(RidePhase.CANCELLED);
        publish(ride, RideEventType.RIDE_CANCELLED, actorId, actorRole,
                Map.of("reason", reason == null ? "" : reason));
        sessions.broadcast(DISPATCH_TOPIC, RideEvent.system("REQUEST_TAKEN", rideId,
                Map.of("rideId", rideId, "cancelled", true)));
        return Optional.of(ride);
    }

    /** Route deviation. Tier 0 on the rider's phone. */
    public Optional<Ride> deviation(String rideId, String note) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        publish(ride, RideEventType.ROUTE_DEVIATION, "server", "SYSTEM",
                Map.of("note", note == null ? "" : note));
        return Optional.of(ride);
    }

    // ===================================================================================
    //  Replay
    // ===================================================================================

    /**
     * Replays a ride's event log to one freshly reconnected session.
     *
     * <p>Every replayed event keeps its original {@code eventId}, so a client that already
     * handled it will discard it. This is the mechanism, not a side effect: without stable ids,
     * "replay everything since you left" and "say everything twice" are the same operation.
     *
     * @param afterSeq client's last-seen sequence number; 0 replays the whole retained log
     * @return how many events were sent
     */
    public int replayTo(String rideId, long afterSeq, org.springframework.web.socket.WebSocketSession session) {
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return 0;
        }
        List<RideEvent> pending = ride.eventsAfter(afterSeq);
        for (RideEvent event : pending) {
            sessions.sendTo(session, event);
        }
        // Bookend so the client knows the backlog is done and live events resume. It is a
        // transport notice, not a ride event, so it is not logged against the ride.
        sessions.sendTo(session, RideEvent.system("REPLAY_COMPLETE", rideId, Map.of(
                "replayed", pending.size(),
                "lastSeq", ride.lastSeq(),
                "phase", ride.phase().name()
        )));
        log.info("REPLAY ride={} afterSeq={} -> {} event(s)", rideId, afterSeq, pending.size());
        return pending.size();
    }

    // ===================================================================================
    //  Internals
    // ===================================================================================

    /** Records an event against a ride's log without broadcasting it. */
    private RideEvent record(Ride ride, RideEventType type, String senderId, String role,
                             Map<String, Object> payload) {
        return ride.append(RideEvent.now(type.name(), ride.rideId(), senderId, role, payload));
    }

    /** Records an event and then broadcasts it to everyone on the ride topic. */
    // ===================================================================================
    //  Payment
    // ===================================================================================

    /**
     * Records what the rider's phone said its UPI app claimed.
     *
     * <p>Deliberately never reaches CONFIRMED. The claim crosses an Intent boundary on the
     * rider's own device and can be wrong, stale or forged, so the most it can justify is
     * REPORTED. Treating it as proof would mean eventually telling a blind rider their fare is
     * settled when it is not — and unlike a sighted rider they cannot glance at a screen and
     * catch it.
     *
     * <p>A FAILURE is the exception and is trusted at once: a false failure costs one retry,
     * whereas a false success costs an unpaid fare nobody notices.
     */
    public synchronized Optional<Ride> reportPayment(String rideId, String status, String txnRef) {
        Ride ride = rides.get(rideId);
        if (ride == null) return Optional.empty();

        // A settled fare is never downgraded by a late or stale claim from the phone. Without
        // this, a UPI app returning FAILURE after the gateway already confirmed would tell the
        // driver an already-paid fare had failed.
        if (ride.paymentStatus() == Ride.PaymentStatus.CONFIRMED) {
            log.info("PAYMENT_REPORT_IGNORED ride={} already CONFIRMED", rideId);
            return Optional.of(ride);
        }

        String claimed = status == null ? "" : status.trim().toUpperCase();
        Ride.PaymentStatus next = switch (claimed) {
            case "FAILURE", "FAILED" -> Ride.PaymentStatus.FAILED;
            default -> Ride.PaymentStatus.REPORTED;
        };

        ride.paymentStatus(next);
        ride.paymentRef(txnRef);
        log.info("PAYMENT_REPORTED ride={} claimed=\"{}\" stored={} ref={}",
                rideId, claimed, next, ride.paymentRef());
        publishPayment(ride, next == Ride.PaymentStatus.FAILED ? "Your payment app reported a failure" : "");
        return Optional.of(ride);
    }

    /**
     * The only transition that means money actually arrived.
     *
     * <p>In production this is driven by the payment provider's webhook. On a plain
     * {@code upi://} deep link there is no provider and therefore no webhook, so nothing calls
     * this automatically — which is a limitation stated in the open rather than papered over
     * by having the client confirm its own payment.
     */
    public synchronized Optional<Ride> confirmPayment(String rideId) {
        return confirmPayment(rideId, null);
    }

    /**
     * Marks the fare paid on the gateway's word, recording its bank reference, and tells both
     * phones. Called by {@code MockPaymentGateway} today and by a provider webhook later.
     *
     * @param bankRef the settlement reference; null or blank keeps whatever was recorded
     */
    public synchronized Optional<Ride> confirmPayment(String rideId, String bankRef) {
        Ride ride = rides.get(rideId);
        if (ride == null) return Optional.empty();
        ride.paymentStatus(Ride.PaymentStatus.CONFIRMED);
        if (bankRef != null && !bankRef.isBlank()) ride.paymentRef(bankRef);
        log.info("PAYMENT_CONFIRMED ride={} ref={}", rideId, ride.paymentRef());
        publishPayment(ride, "");
        return Optional.of(ride);
    }

    /**
     * The gateway refused the payment. Ignored once the fare is confirmed: a failed retry
     * after a successful payment does not un-pay the ride.
     */
    public synchronized Optional<Ride> failPayment(String rideId, String reason) {
        Ride ride = rides.get(rideId);
        if (ride == null) return Optional.empty();
        if (ride.paymentStatus() == Ride.PaymentStatus.CONFIRMED) return Optional.of(ride);
        ride.paymentStatus(Ride.PaymentStatus.FAILED);
        log.info("PAYMENT_FAILED ride={} reason=\"{}\"", rideId, reason);
        publishPayment(ride, reason);
        return Optional.of(ride);
    }

    /** Tells everyone still on the ride topic — in practice the driver's Complete screen. */
    private void publishPayment(Ride ride, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", ride.paymentStatus().name());
        payload.put("paymentRef", ride.paymentRef());
        payload.put("fareRupees", ride.fareRupees());
        if (reason != null && !reason.isBlank()) payload.put("reason", reason);
        publish(ride, RideEventType.PAYMENT_UPDATED, "server", "SYSTEM", payload);
    }

    private RideEvent publish(Ride ride, RideEventType type, String senderId, String role,
                              Map<String, Object> payload) {
        RideEvent event = record(ride, type, senderId, role, payload);
        sessions.broadcast(ride.rideId(), event);
        for (java.util.function.BiConsumer<Ride, RideEvent> listener : eventListeners) {
            // A listener failing must never undo or hide a transition that already happened.
            try {
                listener.accept(ride, event);
            } catch (RuntimeException e) {
                log.warn("EVENT_LISTENER_FAILED ride={} type={} : {}", ride.rideId(), type, e.toString());
            }
        }
        return event;
    }

    /**
     * A phase change that is only legal from certain phases.
     *
     * @param allowedFrom phases this transition may be made from; empty means any non-terminal
     */
    private Optional<Ride> transition(String rideId, RidePhase to, RideEventType type,
                                      String actorId, Map<String, Object> payload,
                                      RidePhase... allowedFrom) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        if (allowedFrom.length > 0) {
            boolean ok = false;
            for (RidePhase from : allowedFrom) {
                if (ride.phase() == from) { ok = true; break; }
            }
            if (!ok) {
                log.warn("TRANSITION_REFUSED ride={} from={} to={}", rideId, ride.phase(), to);
                return Optional.empty();
            }
        }
        ride.phase(to);
        publish(ride, type, actorId, "DRIVER", payload);
        return Optional.of(ride);
    }

    /**
     * A three-digit boarding code, spoken as "four, seven, two".
     *
     * <p>Three digits, not six: the driver has to say it aloud and the rider has to hold it in
     * working memory while a car door is open on a street. A longer code is more secure against
     * an attacker who is not present, and this code exists entirely to defend against one who is.
     *
     * <p>{@link SecureRandom} rather than {@code Math.random()} because a predictable code is
     * the same as no code — anyone able to guess the next one could walk up and say it.
     */
    private String generateBoardingCode() {
        int a = random.nextInt(10);
        int b = random.nextInt(10);
        int c = random.nextInt(10);
        return a + "-" + b + "-" + c;
    }
}
