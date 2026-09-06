package com.cabeye.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One message on a ride's WebSocket topic.
 *
 * <p>This is the single envelope used in <em>both</em> directions (rider&nbsp;&rarr;&nbsp;server
 * and server&nbsp;&rarr;&nbsp;rider/driver). Keeping one shape means the Android client needs
 * exactly one parser, which matters because parsing sits inside the 2000&nbsp;ms latency
 * budget.
 *
 * <p>Serialised as JSON by Jackson, e.g.
 * <pre>{@code
 * { "eventId":"3f2a…", "seq":7,
 *   "type":"DRIVER_LOCATION", "rideId":"demo",
 *   "senderId":"driver-1", "senderRole":"DRIVER",
 *   "payload":{"distanceMeters":120,"bearingDeg":45.0},
 *   "ts":"2026-08-24T10:15:30.123Z" }
 * }</pre>
 *
 * <h2>Why {@code eventId} and {@code seq} exist</h2>
 * A reconnecting client is <b>replayed</b> the ride's event log, so it will legitimately
 * receive events it has already acted on. Acting on them twice would mean the rider hears
 * "Your car has arrived" a second time, which — for someone who cannot glance at the screen
 * to check — is indistinguishable from a second car actually arriving.
 *
 * <ul>
 *   <li>{@code eventId} is a UUID stamped once, at creation. It survives replay unchanged,
 *       which is precisely what makes client-side de-duplication possible. Re-generating it
 *       on the way out would defeat the entire mechanism.</li>
 *   <li>{@code seq} is a per-ride monotonic counter, so a client can ask for "everything
 *       after 7" instead of re-reading the whole log, and can detect a gap.</li>
 * </ul>
 *
 * <p>{@code payload} is an untyped map on purpose: the event vocabulary is still moving, and
 * a loose map avoids a round of DTO churn per new event type.
 *
 * @param eventId    stable unique id; the client de-duplicates on this
 * @param seq        per-ride monotonic sequence number; 0 for events with no ride log
 * @param type       event name — see {@link RideEventType} for the ride-lifecycle vocabulary
 * @param rideId     the ride topic this belongs to
 * @param senderId   value of the {@code X-User-Id} header / WebSocket query param
 * @param senderRole {@code RIDER}, {@code DRIVER} or {@code SYSTEM}
 * @param payload    free-form body; may be {@code null}
 * @param ts         server-assigned timestamp
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RideEvent(
        String eventId,
        long seq,
        String type,
        String rideId,
        String senderId,
        String senderRole,
        Map<String, Object> payload,
        Instant ts
) {

    /** Convenience factory that stamps a fresh {@code eventId} and the current server time. */
    public static RideEvent now(String type,
                                String rideId,
                                String senderId,
                                String senderRole,
                                Map<String, Object> payload) {
        return new RideEvent(
                UUID.randomUUID().toString(), 0L,
                type, rideId, senderId, senderRole, payload, Instant.now());
    }

    /** A server-originated event (no human sender), e.g. a system notice. */
    public static RideEvent system(String type, String rideId, Map<String, Object> payload) {
        return now(type, rideId, "server", "SYSTEM", payload);
    }

    /**
     * Returns a copy carrying a sequence number.
     *
     * <p>Note {@code eventId} is deliberately carried over rather than regenerated — see the
     * class javadoc. This is the one place the distinction between the two fields matters.
     */
    public RideEvent withSeq(long assignedSeq) {
        return new RideEvent(eventId, assignedSeq, type, rideId, senderId, senderRole, payload, ts);
    }
}
