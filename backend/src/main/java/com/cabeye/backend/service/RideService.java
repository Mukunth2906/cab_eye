package com.cabeye.backend.service;

import com.cabeye.backend.fare.FareCalculator;
import com.cabeye.backend.fare.TripDistanceTracker;
import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.model.RideEventType;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.model.RideStop;
import com.cabeye.backend.websocket.RideSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final com.cabeye.backend.redis.RedisBridge redisBridge;
    private final com.cabeye.backend.store.Table<com.cabeye.backend.model.RideRecord> rideTable;

    public RideService(RideSessionManager sessions) {
        this(sessions, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RideService(RideSessionManager sessions,
                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                       com.cabeye.backend.store.DataDirectory data,
                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                       com.cabeye.backend.redis.RedisBridge redisBridge) {
        this.sessions = sessions;
        this.redisBridge = redisBridge;
        this.rideTable = data != null ? data.table("rides", com.cabeye.backend.model.RideRecord.class) : null;
    }

    /**
     * Prices trips from what they measured. Set by Spring from the configured rates (see
     * {@code FareConfig}); a RideService built by hand in a test keeps the defaults, so the
     * constructor above stays the one the tests already use.
     */
    private volatile FareCalculator fares = new FareCalculator();

    @Autowired(required = false)
    public void setFareCalculator(FareCalculator fares) {
        if (fares != null) this.fares = fares;
    }

    /** A TRIP_PROGRESS is published each time the measured distance grows by this much. */
    static final int PROGRESS_STEP_METERS = 100;

    /** How far a phone's GPS time may be from the server's clock before it is ignored. */
    static final long MAX_FIX_CLOCK_SKEW_MS = 10 * 60_000L;

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
        if (rideId == null) return Optional.empty();
        Ride local = rides.get(rideId);

        if (rideTable != null) {
            Optional<com.cabeye.backend.model.RideRecord> record = rideTable.get(rideId);
            if (record.isPresent()) {
                com.cabeye.backend.model.RideRecord r = record.get();
                if (local == null || r.lastSeq > local.lastSeq()) {
                    try {
                        Ride restored = Ride.fromRecord(r);
                        rides.put(rideId, restored);
                        return Optional.of(restored);
                    } catch (RuntimeException e) {
                        log.warn("Failed to restore ride {} from persistence: {}", rideId, e.getMessage());
                    }
                }
            }
        }
        return Optional.ofNullable(local);
    }

    /** @return true when this ride genuinely exists — the scope check for the debug endpoint. */
    public boolean exists(String rideId) {
        return find(rideId).isPresent();
    }

    private Ride getRide(String rideId) {
        return find(rideId).orElse(null);
    }

    public Collection<Ride> all() {
        return rides.values();
    }

    /** Rides a driver may accept: requested, unassigned, not terminal. */
    public List<Ride> openRequests() {
        if (rideTable != null) {
            for (com.cabeye.backend.model.RideRecord r : rideTable.all()) {
                if ("REQUESTED".equals(r.phase) && r.driverId == null) {
                    find(r.rideId); // refresh
                }
            }
        }
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
        return create(riderId, destination, destinationAddress, destinationLatitude,
                destinationLongitude, destinationPlaceId, pickupLatitude, pickupLongitude,
                contactName, contactPhone, dropNote, rideType, List.of());
    }

    /**
     * Creates a ride with intermediate stops (Uber/Rapido-style multi-stop). {@code stops} are
     * validated by {@link #prepareNewStops} — at most {@link #MAX_STOPS}, each with a name.
     *
     * @throws StopRefused with a speakable sentence when the stop list is not acceptable
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
                       String rideType,
                       List<RideStop> stops) {
        List<RideStop> prepared = prepareNewStops(stops, 0);
        Long clusterSeq = (redisBridge != null && redisBridge.isEnabled()) ? redisBridge.incrementRideCounter() : null;
        String rideId = "ride-" + (clusterSeq != null ? clusterSeq : rideCounter.incrementAndGet());
        String code = generateBoardingCode();

        Ride ride = new Ride(rideId, riderId, destination, destinationAddress,
                destinationLatitude, destinationLongitude, destinationPlaceId,
                pickupLatitude, pickupLongitude, contactName, contactPhone, dropNote, rideType, code);
        if (!prepared.isEmpty()) ride.withStops(list -> list.addAll(prepared));
        rides.put(rideId, ride);

        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", rideId);
        if (!prepared.isEmpty()) payload.put("stops", ride.copyStops());
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

        log.info("RIDE_CREATED ride={} rider={} destination=\"{}\" type={} code={} stops={}",
                rideId, riderId, destination, rideType, code, prepared.size());
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        publish(ride, RideEventType.POSITION_PRESET, driverId, "DRIVER", Map.of("text", text));
        return Optional.of(ride);
    }

    /** Audio beacon, panned on the rider's phone to the bearing given here. */
    public Optional<Ride> beacon(String rideId, String driverId, float bearingDeg) {
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        // Mid-trip, the only reason to check the code again is a rider getting back in at a
        // WAIT stop. It never touches the pickup confirmation, which is already history.
        if (ride.phase() == RidePhase.IN_TRIP) {
            return riderReturned(ride, riderId, matched);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
        if (ride == null || ride.phase() != RidePhase.SEATED) {
            log.warn("TRIP_START_REFUSED ride={} phase={} (passenger not confirmed seated)",
                    rideId, ride == null ? "MISSING" : ride.phase());
            return Optional.empty();
        }
        ride.phase(RidePhase.IN_TRIP);
        ride.etaMinutes(etaMinutes);
        ride.startTripMeter(System.currentTimeMillis());
        publish(ride, RideEventType.TRIP_STARTED, driverId, "DRIVER", Map.of(
                "destination", ride.destination(),
                "etaMinutes", etaMinutes
        ));
        return Optional.of(ride);
    }

    /**
     * One GPS fix from the driver's phone during the trip.
     *
     * <p>Only counted while IN_TRIP: fixes from the approach are not part of the journey the
     * rider pays for. Like {@link #location} it does not change the phase. Every
     * {@value #PROGRESS_STEP_METERS} m it publishes TRIP_PROGRESS with the distance so far and
     * the fare that would be charged now, which is also when the ride (and its meter) is saved.
     *
     * @return empty when there is no such ride or it is not in a trip
     */
    public Optional<Ride> tripLocation(String rideId, String driverId, double lat, double lng) {
        return tripLocation(rideId, driverId, lat, lng, null);
    }

    /**
     * @param fixAtMillis when the phone took the fix (its GPS time), or null for "now". Phones
     *                    can send several fixes at once after losing signal; timing them by
     *                    arrival would make real movement look like a teleport and drop it. A
     *                    time more than {@value #MAX_FIX_CLOCK_SKEW_MS} ms from the server's
     *                    clock is not trusted and "now" is used instead.
     */
    public Optional<Ride> tripLocation(String rideId, String driverId, double lat, double lng, Long fixAtMillis) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase() != RidePhase.IN_TRIP) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        long at = fixAtMillis != null && Math.abs(fixAtMillis - now) <= MAX_FIX_CLOCK_SKEW_MS ? fixAtMillis : now;
        ride.addTripFix(lat, lng, at);
        int metres = ride.gpsMeters();
        if (metres - ride.lastProgressMeters() >= PROGRESS_STEP_METERS) {
            int minutes = minutesSince(ride.tripStartedAt(), now);
            int fare = fares.fareRupees(ride.rideType(), metres, minutes);
            ride.liveFare(fare, metres);
            publish(ride, RideEventType.TRIP_PROGRESS, driverId, "DRIVER", Map.of(
                    "distanceMeters", metres,
                    "fareRupees", fare,
                    "minutes", minutes
            ));
        }
        return Optional.of(ride);
    }

    /**
     * Ends the journey and prices it on the server from what the trip measured.
     *
     * <p>This is what the {@code /complete} endpoint calls. The driver's phone no longer names
     * the fare — a fare the client sends could be anything, so it is ignored.
     *
     * <ul>
     *   <li><b>Distance</b>: the GPS meter when it measured anything ("GPS"); otherwise the
     *       straight line from pickup to destination when both are known ("ESTIMATE" — a lower
     *       bound, since roads are never shorter than a straight line); otherwise 0.</li>
     *   <li><b>Minutes</b>: from trip start to now, rounded up, at least 1; 0 if the trip was
     *       never started.</li>
     * </ul>
     */
    public Optional<Ride> completeTrip(String rideId, String driverId) {
        Ride ride = rides.get(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        int minutes = minutesSince(ride.tripStartedAt(), now);
        int metres = ride.gpsMeters();
        String source = "GPS";
        if (metres <= 0) {
            metres = straightLineMeters(ride);
            source = metres > 0 ? "ESTIMATE" : "";
        }
        ride.tripDistance(metres, source);
        int fare = fares.fareRupees(ride.rideType(), metres, minutes);
        log.info("FARE ride={} type={} distance={}m ({}) minutes={} fare=Rs{}",
                rideId, ride.rideType(), metres, source.isEmpty() ? "none" : source, minutes, fare);
        return complete(rideId, driverId, fare, minutes);
    }

    /** Whole minutes from {@code startMillis} to {@code now}, rounded up; 0 when never started. */
    static int minutesSince(Long startMillis, long now) {
        if (startMillis == null || startMillis <= 0) return 0;
        long ms = Math.max(0, now - startMillis);
        return (int) Math.max(1, (ms + 59_999) / 60_000);
    }

    private static int straightLineMeters(Ride ride) {
        if (ride.pickupLatitude() == null || ride.pickupLongitude() == null
                || ride.destinationLatitude() == null || ride.destinationLongitude() == null) {
            return 0;
        }
        return (int) Math.round(TripDistanceTracker.haversineMeters(
                ride.pickupLatitude(), ride.pickupLongitude(),
                ride.destinationLatitude(), ride.destinationLongitude()));
    }

    /**
     * Ends the journey with the given fare and duration.
     *
     * <p>Kept for callers and tests that set the numbers themselves; the app's {@code /complete}
     * goes through {@link #completeTrip}, which measures them.
     */
    public Optional<Ride> complete(String rideId, String driverId, int fareRupees, int durationMinutes) {
        Ride ride = getRide(rideId);
        if (ride == null || ride.phase().isTerminal()) {
            return Optional.empty();
        }
        // A stop still to visit — or a rider still outside at a WAIT stop — means the ride is
        // not over. Ending it would strand a blind passenger at an errand.
        if (ride.hasOpenStops()) {
            log.warn("COMPLETE_REFUSED ride={} (stop {} still open)", rideId, ride.currentStopIndex());
            return Optional.empty();
        }
        ride.phase(RidePhase.COMPLETED);
        ride.fare(fareRupees);
        ride.durationMinutes(durationMinutes);
        publish(ride, RideEventType.TRIP_COMPLETED, driverId, "DRIVER", Map.of(
                "destination", ride.destination(),
                "fareRupees", fareRupees,
                "durationMinutes", durationMinutes,
                "distanceMeters", ride.tripDistanceMeters(),
                "distanceSource", ride.distanceSource()
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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
        Ride ride = getRide(rideId);
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

    // ===================================================================================
    //  Multi-stop rides — see RideStop for the kinds and the safety rule
    // ===================================================================================

    /** Uber allows two or three intermediate stops depending on the city; three here. */
    public static final int MAX_STOPS = 3;

    /** Ten minutes by default: a blind rider's errand takes longer than Uber's three. */
    private volatile int defaultWaitSeconds = 600;

    public void setDefaultWaitSeconds(int seconds) {
        this.defaultWaitSeconds = Math.max(60, seconds);
    }

    public int defaultWaitSeconds() {
        return defaultWaitSeconds;
    }

    /** A refused stop action, with a sentence the phone can speak or the driver can read. */
    public static class StopRefused extends RuntimeException {
        public final int status;

        public StopRefused(int status, String sentence) {
            super(sentence);
            this.status = status;
        }
    }

    /**
     * Cleans a list of new stops: names required, kinds parsed, fresh ids, PENDING, the wait
     * limit stamped. {@code alreadyUsed} counts stops on the ride that still occupy a slot.
     */
    List<RideStop> prepareNewStops(List<RideStop> in, int alreadyUsed) {
        if (in == null || in.isEmpty()) return new ArrayList<>();
        if (in.size() + alreadyUsed > MAX_STOPS) {
            throw new StopRefused(400, "A ride can have up to " + MAX_STOPS + " stops before the destination.");
        }
        List<RideStop> out = new ArrayList<>();
        for (RideStop raw : in) {
            if (raw == null || raw.name == null || raw.name.isBlank()) {
                throw new StopRefused(400, "Every stop needs a place.");
            }
            RideStop s = raw.copy();
            s.name = raw.name.trim();
            if (s.kind == null) s.kind = RideStop.Kind.DROP;
            if (s.stopId == null || s.stopId.isBlank()) s.stopId = "stop-" + Long.toString(random.nextLong() & Long.MAX_VALUE, 36);
            s.status = RideStop.Status.PENDING;
            s.arrivedAt = 0;
            s.waitStartedAt = 0;
            s.doneAt = 0;
            s.riderBack = false;
            s.warnedHalf = false;
            s.warnedLastMinute = false;
            s.overdue = false;
            s.waitLimitSeconds = s.kind == RideStop.Kind.WAIT ? defaultWaitSeconds : 0;
            out.add(s);
        }
        return out;
    }

    /** The car reached the stop it was heading to. Driver only, in order, during the trip. */
    public synchronized Ride stopArrived(String rideId, String driverId, String stopId) {
        Ride ride = requireTrip(rideId);
        RideStop arrived = ride.withStops(list -> {
            RideStop current = currentOpen(list);
            if (current == null) throw new StopRefused(409, "There are no more stops. Drive to the destination.");
            if (stopId != null && !stopId.isBlank() && !stopId.equals(current.stopId)) {
                throw new StopRefused(409, "That isn't the next stop. Stops are visited in order.");
            }
            if (current.status != RideStop.Status.PENDING) {
                throw new StopRefused(409, "You've already arrived at this stop.");
            }
            long now = System.currentTimeMillis();
            current.arrivedAt = now;
            if (current.kind == RideStop.Kind.WAIT) {
                current.status = RideStop.Status.WAITING;
                current.waitStartedAt = now;
            } else {
                current.status = RideStop.Status.ARRIVED;
            }
            return current.copy();
        });
        Map<String, Object> payload = stopPayload(arrived);
        payload.put("waitLimitSeconds", arrived.waitLimitSeconds);
        publish(ride, RideEventType.STOP_ARRIVED, driverId, "DRIVER", payload);
        log.info("STOP_ARRIVED ride={} stop={} kind={} name=\"{}\"", rideId, arrived.index, arrived.kind, arrived.name);
        return ride;
    }

    /**
     * The driver finished the stop: dropped someone, picked someone up, or — at a WAIT stop —
     * the rider is back. A WAIT stop cannot be finished until the rider's phone has heard the
     * code again, so the car can never leave with the wrong person or without its passenger.
     */
    public synchronized Ride stopDone(String rideId, String driverId, String stopId) {
        Ride ride = requireTrip(rideId);
        long now = System.currentTimeMillis();
        RideStop done = ride.withStops(list -> {
            RideStop current = currentOpen(list);
            if (current == null || (stopId != null && !stopId.isBlank() && !stopId.equals(current.stopId))) {
                throw new StopRefused(409, "That stop is already finished.");
            }
            if (current.status == RideStop.Status.PENDING) {
                throw new StopRefused(409, "Tap Arrived at stop first.");
            }
            if (current.kind == RideStop.Kind.WAIT && !current.riderBack) {
                throw new StopRefused(409, "Your passenger is not back yet. Wait for their phone to confirm the code.");
            }
            current.status = RideStop.Status.DONE;
            current.doneAt = now;
            return current.copy();
        });
        Map<String, Object> payload = stopPayload(done);
        payload.put("waitedSeconds", done.waitedSeconds(now));
        payload.put("next", nextLeg(ride));
        publish(ride, RideEventType.STOP_DONE, driverId, "DRIVER", payload);
        log.info("STOP_DONE ride={} stop={} kind={} waited={}s", rideId, done.index, done.kind, done.waitedSeconds(now));
        return ride;
    }

    /** The rider drops a stop they no longer need — one still ahead, or one just reached. */
    public synchronized Ride skipStop(String rideId, String riderId, String stopId) {
        Ride ride = requireLive(rideId);
        RideStop skipped = ride.withStops(list -> {
            RideStop target = null;
            for (RideStop s : list) {
                if (s.stopId.equals(stopId)) target = s;
            }
            if (target == null) throw new StopRefused(404, "I can't find that stop.");
            if (target.status == RideStop.Status.WAITING) {
                throw new StopRefused(409, "You're at that stop now. Get back in and the driver will move on.");
            }
            if (!target.status.isOpen()) throw new StopRefused(409, "That stop is already behind you.");
            target.status = RideStop.Status.SKIPPED;
            target.doneAt = System.currentTimeMillis();
            return target.copy();
        });
        Map<String, Object> payload = stopPayload(skipped);
        payload.put("next", nextLeg(ride));
        publish(ride, RideEventType.STOP_SKIPPED, riderId, "RIDER", payload);
        return ride;
    }

    /**
     * Replaces the stops still ahead (PENDING) with {@code upcoming}, keeping every stop that is
     * finished, skipped or being visited exactly where it is. Covers add, remove and reorder —
     * the rider's phone sends the list it read back and the rider confirmed.
     */
    public synchronized Ride replaceUpcomingStops(String rideId, String riderId, List<RideStop> upcoming) {
        Ride ride = requireLive(rideId);
        ride.withStops(list -> {
            List<RideStop> kept = new ArrayList<>();
            Map<String, RideStop> pendingById = new HashMap<>();
            int used = 0;
            for (RideStop s : list) {
                if (s.status == RideStop.Status.PENDING) {
                    pendingById.put(s.stopId, s);
                } else {
                    kept.add(s);
                    if (s.status != RideStop.Status.SKIPPED) used++;
                }
            }
            List<RideStop> fresh = prepareNewStops(upcoming, used);
            // A stop the rider merely moved keeps its id, so a driver's tap on it still lands.
            for (int i = 0; i < fresh.size(); i++) {
                RideStop wanted = upcoming.get(i);
                if (wanted.stopId != null && pendingById.containsKey(wanted.stopId)) {
                    fresh.get(i).stopId = wanted.stopId;
                }
            }
            list.clear();
            list.addAll(kept);
            list.addAll(fresh);
            return null;
        });
        Map<String, Object> payload = new HashMap<>();
        payload.put("stops", ride.copyStops());
        payload.put("next", nextLeg(ride));
        publish(ride, RideEventType.STOPS_CHANGED, riderId, "RIDER", payload);
        log.info("STOPS_CHANGED ride={} stops={}", rideId, ride.copyStops().size());
        return ride;
    }

    /**
     * Reminders and the overdue alarm for riders out at a WAIT stop. Called by
     * {@link com.cabeye.backend.service.StopWaitMonitor} every few seconds; pure on {@code now}
     * so it is testable without waiting ten minutes.
     */
    public void checkWaits(long now) {
        for (Ride ride : new ArrayList<>(rides.values())) {
            if (ride.phase() != RidePhase.IN_TRIP) continue;
            List<Object[]> due = new ArrayList<>();
            ride.withStops(list -> {
                for (RideStop s : list) {
                    if (s.status != RideStop.Status.WAITING || s.riderBack || s.waitLimitSeconds <= 0) continue;
                    long waited = (now - s.waitStartedAt) / 1000;
                    long left = s.waitLimitSeconds - waited;
                    if (left <= 0 && !s.overdue) {
                        s.overdue = true;
                        due.add(new Object[]{RideEventType.WAIT_OVERDUE, s.copy(), 0L});
                    } else if (left > 0 && left <= 60 && !s.warnedLastMinute) {
                        s.warnedLastMinute = true;
                        s.warnedHalf = true;
                        due.add(new Object[]{RideEventType.WAIT_WARNING, s.copy(), left});
                    } else if (left > 60 && waited >= s.waitLimitSeconds / 2 && !s.warnedHalf) {
                        s.warnedHalf = true;
                        due.add(new Object[]{RideEventType.WAIT_WARNING, s.copy(), left});
                    }
                }
                return null;
            });
            for (Object[] d : due) {
                RideStop s = (RideStop) d[1];
                Map<String, Object> payload = stopPayload(s);
                payload.put("secondsLeft", d[2]);
                payload.put("waitedSeconds", s.waitedSeconds(now));
                publish(ride, (RideEventType) d[0], "server", "SYSTEM", payload);
                if (d[0] == RideEventType.WAIT_OVERDUE) {
                    log.warn("WAIT_OVERDUE ride={} stop={} name=\"{}\" waited={}s", ride.rideId(), s.index, s.name, s.waitedSeconds(now));
                }
            }
        }
    }

    private Ride requireLive(String rideId) {
        Ride ride = getRide(rideId);
        if (ride == null) throw new StopRefused(404, "I can't find that ride any more.");
        if (ride.phase().isTerminal()) throw new StopRefused(409, "That ride has already ended.");
        return ride;
    }

    private Ride requireTrip(String rideId) {
        Ride ride = requireLive(rideId);
        if (ride.phase() != RidePhase.IN_TRIP) throw new StopRefused(409, "Start the trip first.");
        return ride;
    }

    private static RideStop currentOpen(List<RideStop> list) {
        for (RideStop s : list) if (s.status.isOpen()) return s;
        return null;
    }

    /** What the car heads to next: the next open stop, or the destination. */
    private static Map<String, Object> nextLeg(Ride ride) {
        Map<String, Object> next = new HashMap<>();
        for (RideStop s : ride.copyStops()) {
            if (s.status.isOpen()) {
                next.put("type", "STOP");
                next.put("index", s.index);
                next.put("name", s.name);
                next.put("kind", s.kind.name());
                return next;
            }
        }
        next.put("type", "DESTINATION");
        next.put("name", ride.destination());
        return next;
    }

    static Map<String, Object> stopPayload(RideStop s) {
        Map<String, Object> m = new HashMap<>();
        m.put("stopId", s.stopId);
        m.put("index", s.index);
        m.put("kind", s.kind.name());
        m.put("status", s.status.name());
        m.put("name", s.name);
        if (s.address != null) m.put("address", s.address);
        if (s.latitude != null) m.put("latitude", s.latitude);
        if (s.longitude != null) m.put("longitude", s.longitude);
        if (s.note != null) m.put("note", s.note);
        return m;
    }

    private Optional<Ride> riderReturned(Ride ride, String riderId, boolean matched) {
        RideStop back = ride.withStops(list -> {
            RideStop current = currentOpen(list);
            if (current == null || current.status != RideStop.Status.WAITING) return null;
            if (matched) current.riderBack = true;
            return current.copy();
        });
        if (back == null) return Optional.of(ride); // nothing to confirm mid-trip: harmless
        Map<String, Object> payload = stopPayload(back);
        payload.put("matched", matched);
        publish(ride, RideEventType.RIDER_RETURNED, riderId, "RIDER", payload);
        log.info("RIDER_RETURNED ride={} stop={} matched={}", ride.rideId(), back.index, matched);
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
