package com.cabeye.backend.camera;

import com.cabeye.backend.model.Ride;
import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.model.RidePhase;
import com.cabeye.backend.service.RideService;
import com.cabeye.backend.websocket.RideSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Help me find my passenger": the rider's back camera, shown live to the driver.
 *
 * <h2>The flow</h2>
 * <pre>
 *   driver taps SEE RIDER'S VIEW  → request()  → CAMERA_REQUESTED  → rider's phone asks by voice
 *   rider says yes                → answer()   → CAMERA_STARTED    → rider streams CAMERA_FRAME
 *   rider says no / no answer     → answer()   → CAMERA_DECLINED
 *   any stop condition            → stop()     → CAMERA_STOPPED {reason}
 * </pre>
 *
 * <h2>The rules, all enforced here rather than trusted to either phone</h2>
 * <ul>
 *   <li>Only the driver assigned to the ride can ask, and only before pickup (assigned, on
 *       the way, or arrived) and before the boarding code is confirmed.</li>
 *   <li>Nothing streams until the rider says yes.</li>
 *   <li>Frames are relayed only while LIVE, only from the ride's own rider, and only to the
 *       driver's connection — never back to the rider, never stored, never in the event log.</li>
 *   <li>It switches itself off when the code is confirmed, the passenger is seated, the trip
 *       starts or ends, the ride is cancelled, or {@link #MAX_LIVE_MS} has passed.</li>
 * </ul>
 *
 * <p>Camera events are broadcast but deliberately <em>not</em> added to the ride's replayable
 * event log. A reconnecting phone must not replay a two-minute-old "may I see your camera?" and
 * ask the rider again. Who started and stopped the camera is still recorded, in the server log.
 */
@Service
public class RideCameraService {

    private static final Logger log = LoggerFactory.getLogger(RideCameraService.class);

    /** The camera turns itself off after this long, whatever else happens. */
    public static final long MAX_LIVE_MS = 3 * 60_000L;

    /** An unanswered request lapses after this long, so the driver is not left waiting. */
    public static final long ANSWER_TIMEOUT_MS = 45_000L;

    /** Frames larger than this (base64 characters) are dropped: one small JPEG, not a photo. */
    public static final int MAX_FRAME_CHARS = 60_000;

    /** At most ~10 frames a second per ride, whatever the phone sends. */
    private static final long MIN_FRAME_GAP_MS = 90L;

    public enum State { OFF, REQUESTED, LIVE }

    /** What a request/answer/stop call produced, for the controller to turn into HTTP. */
    public enum Outcome { OK, NOT_FOUND, FORBIDDEN, CONFLICT }

    public record Result(Outcome outcome, State state, String message) {
        static Result ok(State state) { return new Result(Outcome.OK, state, ""); }
        static Result fail(Outcome outcome, String message) { return new Result(outcome, State.OFF, message); }
    }

    /**
     * One ride's camera. {@code generation} changes on every transition, so a timer set for an
     * earlier session can never switch off a later one.
     */
    private record Session(State state, long since, long generation) {}

    private static final Set<RidePhase> BEFORE_PICKUP =
            Set.of(RidePhase.ASSIGNED, RidePhase.ENROUTE, RidePhase.ARRIVED);

    private final RideService rides;
    private final RideSessionManager sessions;
    private final Map<String, Session> byRide = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFrameAt = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "camera-timer");
        t.setDaemon(true);
        return t;
    });

    public RideCameraService(RideService rides, RideSessionManager sessions) {
        this.rides = rides;
        this.sessions = sessions;
        rides.onEvent(this::onRideEvent);
    }

    public State state(String rideId) {
        Session s = byRide.get(rideId);
        return s == null ? State.OFF : s.state();
    }

    // ===================================================================================
    //  Driver: ask to see
    // ===================================================================================

    public synchronized Result request(String rideId, String driverId) {
        Ride ride = rides.find(rideId).orElse(null);
        if (ride == null) return Result.fail(Outcome.NOT_FOUND, "That ride no longer exists.");
        if (ride.driverId() == null || !ride.driverId().equals(driverId)) {
            return Result.fail(Outcome.FORBIDDEN, "Only the driver on this ride can ask for the camera.");
        }
        if (!BEFORE_PICKUP.contains(ride.phase())) {
            return Result.fail(Outcome.CONFLICT, "The camera is only for finding your passenger before pickup.");
        }
        if (ride.codeConfirmed()) {
            return Result.fail(Outcome.CONFLICT, "Your passenger has already confirmed the code.");
        }

        State current = state(rideId);
        // Already asked or already live: say so rather than asking the rider a second time.
        if (current != State.OFF) return Result.ok(current);

        long gen = generations.incrementAndGet();
        byRide.put(rideId, new Session(State.REQUESTED, now(), gen));
        broadcast(ride, "CAMERA_REQUESTED", driverId, "DRIVER",
                Map.of("driverName", nullToEmpty(ride.driverName()),
                        "answerSeconds", ANSWER_TIMEOUT_MS / 1000));
        log.info("CAMERA_REQUESTED ride={} driver={}", rideId, driverId);

        timer.schedule(() -> expireRequest(rideId, gen), ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return Result.ok(State.REQUESTED);
    }

    // ===================================================================================
    //  Rider: yes or no
    // ===================================================================================

    public synchronized Result answer(String rideId, String riderId, boolean accept, String reason) {
        Ride ride = rides.find(rideId).orElse(null);
        if (ride == null) return Result.fail(Outcome.NOT_FOUND, "That ride no longer exists.");
        if (riderId == null || !riderId.equals(ride.riderId())) {
            return Result.fail(Outcome.FORBIDDEN, "Only the passenger can answer.");
        }
        Session s = byRide.get(rideId);
        if (s == null || s.state() != State.REQUESTED) {
            return Result.fail(Outcome.CONFLICT, "Your driver is no longer asking to see the camera.");
        }

        if (!accept) {
            byRide.remove(rideId);
            String why = reason == null || reason.isBlank() ? "RIDER_DECLINED" : reason;
            broadcast(ride, "CAMERA_DECLINED", riderId, "RIDER", Map.of("reason", why));
            log.info("CAMERA_DECLINED ride={} rider={} reason={}", rideId, riderId, why);
            return Result.ok(State.OFF);
        }

        long gen = generations.incrementAndGet();
        byRide.put(rideId, new Session(State.LIVE, now(), gen));
        lastFrameAt.remove(rideId);
        broadcast(ride, "CAMERA_STARTED", riderId, "RIDER", Map.of("maxSeconds", MAX_LIVE_MS / 1000));
        log.info("CAMERA_STARTED ride={} rider={}", rideId, riderId);

        timer.schedule(() -> expireLive(rideId, gen), MAX_LIVE_MS, TimeUnit.MILLISECONDS);
        return Result.ok(State.LIVE);
    }

    // ===================================================================================
    //  Either side, or the server: stop
    // ===================================================================================

    /** Idempotent: stopping a camera that is already off is not an error. */
    public synchronized Result stop(String rideId, String actorId, String actorRole, String reason) {
        Ride ride = rides.find(rideId).orElse(null);
        if (ride == null) return Result.fail(Outcome.NOT_FOUND, "That ride no longer exists.");
        if ("DRIVER".equals(actorRole) && !actorId.equals(ride.driverId())) {
            return Result.fail(Outcome.FORBIDDEN, "Only the driver on this ride can stop the camera.");
        }
        if ("RIDER".equals(actorRole) && !actorId.equals(ride.riderId())) {
            return Result.fail(Outcome.FORBIDDEN, "Only the passenger can stop the camera.");
        }
        end(ride, actorId, actorRole, reason);
        return Result.ok(State.OFF);
    }

    // ===================================================================================
    //  Frames
    // ===================================================================================

    /**
     * Relays one frame from the rider to the driver, if everything about it is allowed.
     *
     * @return true when it was passed on
     */
    public boolean relayFrame(String rideId, String senderId, String senderRole, Map<String, Object> payload) {
        Session s = byRide.get(rideId);
        if (s == null || s.state() != State.LIVE) return false;
        if (!"RIDER".equals(senderRole)) return false;

        Ride ride = rides.find(rideId).orElse(null);
        if (ride == null || senderId == null || !senderId.equals(ride.riderId())) return false;

        if (now() - s.since() > MAX_LIVE_MS) {
            expireLive(rideId, s.generation());
            return false;
        }

        Object jpeg = payload == null ? null : payload.get("jpeg");
        if (!(jpeg instanceof String data) || data.isEmpty() || data.length() > MAX_FRAME_CHARS) return false;

        long t = now();
        Long last = lastFrameAt.get(rideId);
        if (last != null && t - last < MIN_FRAME_GAP_MS) return false;
        lastFrameAt.put(rideId, t);

        Map<String, Object> out = new HashMap<>();
        out.put("jpeg", data);
        Object seq = payload.get("n");
        if (seq instanceof Number) out.put("n", seq);
        return sessions.sendToRole(rideId, "DRIVER",
                RideEvent.now("CAMERA_FRAME", rideId, senderId, "RIDER", out)) > 0;
    }

    // ===================================================================================
    //  Automatic stops
    // ===================================================================================

    private void onRideEvent(Ride ride, RideEvent event) {
        if (state(ride.rideId()) == State.OFF) return;
        String reason = switch (event.type()) {
            case "CODE_CONFIRMED" -> Boolean.TRUE.equals(event.payload().get("matched")) ? "CODE_CONFIRMED" : null;
            case "PASSENGER_SEATED" -> "SEATED";
            case "TRIP_STARTED" -> "TRIP_STARTED";
            case "TRIP_COMPLETED" -> "TRIP_COMPLETED";
            case "RIDE_CANCELLED" -> "CANCELLED";
            default -> null;
        };
        if (reason != null) {
            synchronized (this) {
                end(ride, "server", "SYSTEM", reason);
            }
        }
    }

    private synchronized void expireRequest(String rideId, long generation) {
        Session s = byRide.get(rideId);
        if (s == null || s.generation() != generation || s.state() != State.REQUESTED) return;
        byRide.remove(rideId);
        rides.find(rideId).ifPresent(ride -> {
            broadcast(ride, "CAMERA_DECLINED", "server", "SYSTEM", Map.of("reason", "NO_ANSWER"));
            log.info("CAMERA_DECLINED ride={} reason=NO_ANSWER", rideId);
        });
    }

    private synchronized void expireLive(String rideId, long generation) {
        Session s = byRide.get(rideId);
        if (s == null || s.generation() != generation || s.state() != State.LIVE) return;
        rides.find(rideId).ifPresent(ride -> end(ride, "server", "SYSTEM", "TIME_LIMIT"));
    }

    /** Must be called holding this object's lock. */
    private void end(Ride ride, String actorId, String actorRole, String reason) {
        Session previous = byRide.remove(ride.rideId());
        lastFrameAt.remove(ride.rideId());
        if (previous == null) return;
        String why = reason == null || reason.isBlank() ? "STOPPED" : reason;
        broadcast(ride, "CAMERA_STOPPED", actorId, actorRole, Map.of("reason", why, "by", actorRole));
        log.info("CAMERA_STOPPED ride={} by={} ({}) reason={} after={}ms",
                ride.rideId(), actorId, actorRole, why, now() - previous.since());
    }

    private void broadcast(Ride ride, String type, String senderId, String role, Map<String, Object> payload) {
        sessions.broadcast(ride.rideId(), RideEvent.now(type, ride.rideId(), senderId, role, payload));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
