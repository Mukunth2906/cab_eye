package com.cabeye.backend.websocket;

import com.cabeye.backend.model.RideEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entire real-time state of the system: a {@code ConcurrentHashMap} of
 * {@code rideId -> set of live WebSocket sessions}.
 *
 * <p>There is no persistence by design. If the process restarts, every ride is gone —
 * acceptable for an MVP whose claim is about interaction latency, not durability.
 *
 * <h2>Thread safety</h2>
 * Two separate concerns, handled differently:
 * <ul>
 *   <li><b>The map</b> is a {@link ConcurrentHashMap} whose values are concurrent sets, so
 *       join/leave from different Tomcat worker threads is safe without external locking.</li>
 *   <li><b>Each session</b> is <em>not</em> thread-safe. Spring's
 *       {@code WebSocketSession.sendMessage} explicitly forbids concurrent sends, and
 *       violating that throws {@code IllegalStateException: TEXT_PARTIAL_WRITING}. Since a
 *       broadcast can race with a direct reply, every write below is wrapped in
 *       {@code synchronized (session)}. This is the simplest correct fix at MVP scale; if
 *       message volume grows, swap in Spring's {@code ConcurrentWebSocketSessionDecorator}
 *       at join time instead.</li>
 * </ul>
 */
@Component
public class RideSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RideSessionManager.class);

    /** Session attribute keys, written by {@link RideWebSocketHandler} at connect time. */
    public static final String ATTR_RIDE_ID = "rideId";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ROLE    = "role";

    /** rideId -> live sessions on that ride. */
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final com.cabeye.backend.redis.RedisBridge redisBridge;
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public RideSessionManager(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RideSessionManager(ObjectMapper objectMapper,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              com.cabeye.backend.redis.RedisBridge redisBridge) {
        this.objectMapper = objectMapper;
        this.redisBridge = redisBridge;
    }

    public String instanceId() {
        return instanceId;
    }

    // -------------------------------------------------------------------------------
    //  join / leave
    // -------------------------------------------------------------------------------

    /**
     * Adds a session to a ride's room, creating the room if this is the first participant.
     *
     * @param rideId  ride topic to join
     * @param session the freshly established session
     */
    public void join(String rideId, WebSocketSession session) {
        rooms.computeIfAbsent(rideId, id -> ConcurrentHashMap.newKeySet())
             .add(session);

        log.info("JOIN  ride={} user={} role={} -> {} session(s) on this ride",
                rideId,
                session.getAttributes().get(ATTR_USER_ID),
                session.getAttributes().get(ATTR_ROLE),
                sessionCount(rideId));
    }

    /**
     * Removes a session from its ride's room. Safe to call twice, and safe to call for a
     * session that never successfully joined.
     *
     * <p>Empty rooms are deleted so the map does not grow without bound over a long demo.
     *
     * @param rideId  ride topic to leave; {@code null} is ignored
     * @param session the session to drop
     */
    public void leave(String rideId, WebSocketSession session) {
        if (rideId == null) {
            return;
        }
        Set<WebSocketSession> room = rooms.get(rideId);
        if (room == null) {
            return;
        }
        room.remove(session);

        // Prune the room if it emptied. `remove(key, value)` is the atomic form: it only
        // deletes if the set is still the empty one we just observed, so we cannot race
        // with a concurrent join and discard a live participant.
        if (room.isEmpty()) {
            rooms.remove(rideId, room);
        }

        log.info("LEAVE ride={} user={} -> {} session(s) remain",
                rideId,
                session.getAttributes().get(ATTR_USER_ID),
                sessionCount(rideId));
    }

    // -------------------------------------------------------------------------------
    //  broadcast & cluster synchronization
    // -------------------------------------------------------------------------------

    /**
     * Sends an event to every open session on a ride across all cluster instances.
     *
     * @param rideId ride topic
     * @param event  event to serialise and deliver
     * @return number of sessions the event was actually written to locally
     */
    public int broadcast(String rideId, RideEvent event) {
        return broadcastExcept(rideId, event, null);
    }

    /**
     * Sends an event to every open session on a ride except one, and fans out to cluster peer nodes.
     *
     * @param rideId  ride topic
     * @param event   event to serialise and deliver
     * @param exclude session to skip; {@code null} means send to everyone
     * @return number of sessions the event was actually written to locally
     */
    public int broadcastExcept(String rideId, RideEvent event, WebSocketSession exclude) {
        String excludeId = exclude != null ? exclude.getId() : null;
        int delivered = broadcastLocal(rideId, event, excludeId);

        if (redisBridge != null && redisBridge.isEnabled()) {
            redisBridge.publish(new com.cabeye.backend.redis.ClusterMessage(
                    instanceId, rideId, null, excludeId, event));
        }

        return delivered;
    }

    /**
     * Delivers an event to local sessions connected directly to this JVM.
     */
    public int broadcastLocal(String rideId, RideEvent event, String excludeSessionId) {
        Set<WebSocketSession> room = rooms.get(rideId);
        if (room == null || room.isEmpty()) {
            log.debug("BROADCAST ride={} type={} -> no local listeners", rideId, event.type());
            return 0;
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.error("Could not serialise event type={} for ride={}", event.type(), rideId, e);
            return 0;
        }

        int delivered = 0;
        for (WebSocketSession session : room) {
            if ((excludeSessionId != null && excludeSessionId.equals(session.getId())) || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("Send failed on ride={} session={}, dropping it", rideId, session.getId(), e);
                room.remove(session);
            }
        }

        log.debug("BROADCAST ride={} type={} -> {} local session(s)", rideId, event.type(), delivered);
        return delivered;
    }

    /**
     * Sends an event only to the sessions on a ride that connected with the given role.
     * Propagates to cluster peers via Redis.
     *
     * @return number of sessions the event was written to locally
     */
    public int sendToRole(String rideId, String role, RideEvent event) {
        int delivered = sendToRoleLocal(rideId, role, event);

        if (redisBridge != null && redisBridge.isEnabled()) {
            redisBridge.publish(new com.cabeye.backend.redis.ClusterMessage(
                    instanceId, rideId, role, null, event));
        }

        return delivered;
    }

    /**
     * Delivers an event to matching role sessions connected to this JVM.
     */
    public int sendToRoleLocal(String rideId, String role, RideEvent event) {
        Set<WebSocketSession> room = rooms.get(rideId);
        if (room == null || room.isEmpty()) return 0;
        final String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.error("Could not serialise event type={} for ride={}", event.type(), rideId, e);
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : room) {
            if (!session.isOpen() || !role.equals(session.getAttributes().get(ATTR_ROLE))) continue;
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("Send failed on ride={} session={}, dropping it", rideId, session.getId(), e);
                room.remove(session);
            }
        }
        return delivered;
    }

    /**
     * Receives an inbound event from Redis Pub/Sub broadcast by a peer cluster instance.
     */
    public void receiveClusterMessage(com.cabeye.backend.redis.ClusterMessage msg) {
        if (msg == null || instanceId.equals(msg.getSenderInstanceId())) {
            return; // ignore self-published messages
        }
        if (msg.getRole() != null) {
            sendToRoleLocal(msg.getRideId(), msg.getRole(), msg.getEvent());
        } else {
            broadcastLocal(msg.getRideId(), msg.getEvent(), msg.getExcludeSessionId());
        }
    }

    /**
     * Stores high-frequency GPS ping in Redis rather than hitting relational DB.
     */
    public void recordDriverLocation(String driverId, Map<String, Object> payload) {
        if (redisBridge != null && redisBridge.isEnabled() && driverId != null && payload != null) {
            try {
                redisBridge.recordDriverLocation(driverId, objectMapper.writeValueAsString(payload));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Sends an event to a single session (a direct reply rather than a broadcast).
     *
     * @param session target session
     * @param event   event to serialise and deliver
     */
    public void sendTo(WebSocketSession session, RideEvent event) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.warn("Direct send failed on session={}", session.getId(), e);
        }
    }

    // -------------------------------------------------------------------------------
    //  introspection (used by the health/debug endpoints)
    // -------------------------------------------------------------------------------

    /** @return number of live sessions on a ride, or 0 if the ride has no room. */
    public int sessionCount(String rideId) {
        Set<WebSocketSession> room = rooms.get(rideId);
        return room == null ? 0 : room.size();
    }

    /** @return number of rides that currently have at least one participant. */
    public int activeRideCount() {
        return rooms.size();
    }

    /** @return an unmodifiable snapshot of {@code rideId -> session count}, for debugging. */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        rooms.forEach((rideId, sessions) -> out.put(rideId, sessions.size()));
        return Collections.unmodifiableMap(out);
    }
}
