package com.cabeye.backend.websocket;

import com.cabeye.backend.model.RideEvent;
import com.cabeye.backend.service.RideService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Raw (non-STOMP) WebSocket endpoint for one ride topic.
 *
 * <h2>Connecting</h2>
 * <pre>
 *   wss://host/ws/ride?rideId=ride-1001&amp;userId=rider-1&amp;role=RIDER&amp;lastSeq=7
 * </pre>
 * Identity travels as query parameters rather than the {@code X-User-Id} / {@code X-Role}
 * headers used by the REST side, for one practical reason: the browser {@code WebSocket}
 * constructor cannot set custom headers, and being able to test this endpoint from a browser
 * tab is worth more than header symmetry. Headers are still read when present, so OkHttp may
 * send either.
 *
 * <h2>{@code lastSeq} and replay</h2>
 * A reconnecting client sends the sequence number of the last event it actually processed. The
 * server replays everything after it. Every replayed event keeps its original {@code eventId},
 * so a client that already acted on one discards it rather than acting twice — which for this
 * product means the rider is not told a second time that a car has arrived.
 *
 * <p>Replay is a <em>safety net</em>, not the primary reconciliation path. The client's first
 * move after a reconnect is {@code GET /rides/{id}}, and it announces the difference between
 * that snapshot and what it last said aloud. Replay exists so nothing is silently lost, not so
 * a rider has to listen to ninety seconds of backlog.
 *
 * <p>There is no authentication. That is intentional per the brief.
 */
@Component
public class RideWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RideWebSocketHandler.class);

    /** Used when a client connects without naming a ride — keeps manual testing frictionless. */
    private static final String DEFAULT_RIDE_ID = "demo";

    private final RideSessionManager sessions;
    private final ObjectMapper objectMapper;
    private final RideService rideService;

    /**
     * @param rideService {@code @Lazy} to break the construction cycle: {@code RideService}
     *   needs the session manager to broadcast, this handler needs the service to replay, and
     *   Spring's {@code WebSocketConfig} pulls the handler in at configuration time.
     */
    public RideWebSocketHandler(RideSessionManager sessions,
                                ObjectMapper objectMapper,
                                @Lazy RideService rideService) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.rideService = rideService;
    }

    // -------------------------------------------------------------------------------
    //  lifecycle
    // -------------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String rideId = param(session, "rideId", "X-Ride-Id", DEFAULT_RIDE_ID);
        String userId = param(session, "userId", "X-User-Id", "anonymous");
        String role   = param(session, "role",   "X-Role",    "RIDER").toUpperCase();
        long lastSeq  = parseLong(param(session, "lastSeq", "X-Last-Seq", "0"));

        session.getAttributes().put(RideSessionManager.ATTR_RIDE_ID, rideId);
        session.getAttributes().put(RideSessionManager.ATTR_USER_ID, userId);
        session.getAttributes().put(RideSessionManager.ATTR_ROLE,    role);

        sessions.join(rideId, session);

        // Immediate ack. The rider app uses this as proof the transport is healthy before it
        // promises the user anything out loud — "silence must mean something" only holds if the
        // app can tell a quiet system from a broken one.
        sessions.sendTo(session, RideEvent.system("CONNECTED", rideId, Map.of(
                "userId",       userId,
                "role",         role,
                "participants", sessions.sessionCount(rideId)
        )));

        // Catch the client up on anything it missed while it was away.
        if (rideService.exists(rideId)) {
            rideService.replayTo(rideId, lastSeq, session);
        }

        // Tell the other party someone arrived (the driver app cares about this).
        sessions.broadcastExcept(rideId,
                RideEvent.system("PARTICIPANT_JOINED", rideId, Map.of("userId", userId, "role", role)),
                session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String rideId = (String) session.getAttributes().get(RideSessionManager.ATTR_RIDE_ID);
        String userId = (String) session.getAttributes().get(RideSessionManager.ATTR_USER_ID);
        String role   = (String) session.getAttributes().get(RideSessionManager.ATTR_ROLE);

        RideEvent incoming;
        try {
            incoming = objectMapper.readValue(message.getPayload(), RideEvent.class);
        } catch (Exception e) {
            log.warn("Unparseable message on ride={} from user={}: {}", rideId, userId, message.getPayload());
            sessions.sendTo(session, RideEvent.system("ERROR", rideId,
                    Map.of("reason", "malformed_json")));
            return;
        }

        if (incoming.type() == null || incoming.type().isBlank()) {
            sessions.sendTo(session, RideEvent.system("ERROR", rideId,
                    Map.of("reason", "missing_type")));
            return;
        }

        // A keepalive, answered and dropped. It exists so a client can prove the socket is
        // genuinely carrying traffic rather than merely appearing open — a half-open TCP
        // connection looks perfectly healthy right up until the rider notices nobody has
        // spoken for two minutes.
        if ("PING".equals(incoming.type())) {
            sessions.sendTo(session, RideEvent.system("PONG", rideId, Map.of()));
            return;
        }

        // Re-stamp identity and timestamp from the session rather than trusting the body. Even
        // without a security layer, a client must not be able to speak as the driver simply by
        // setting a field — that would make the boarding-code flow meaningless.
        RideEvent outgoing = RideEvent.now(
                incoming.type(), rideId, userId, role, incoming.payload());

        // Echo to the other party only. The sender already knows what it just said, and every
        // needless message is latency the rider eventually pays for.
        int delivered = sessions.broadcastExcept(rideId, outgoing, session);

        log.debug("MSG   ride={} from={} type={} -> {} peer(s)", rideId, userId, outgoing.type(), delivered);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String rideId = (String) session.getAttributes().get(RideSessionManager.ATTR_RIDE_ID);
        String userId = (String) session.getAttributes().get(RideSessionManager.ATTR_USER_ID);

        sessions.leave(rideId, session);

        if (rideId != null) {
            sessions.broadcast(rideId, RideEvent.system("PARTICIPANT_LEFT", rideId,
                    Map.of("userId", String.valueOf(userId), "code", status.getCode())));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error on session={}: {}", session.getId(), exception.toString());
        // Let Spring close the session; afterConnectionClosed will do the room cleanup.
    }

    // -------------------------------------------------------------------------------
    //  helpers
    // -------------------------------------------------------------------------------

    /**
     * Reads a connection parameter, preferring the URI query string and falling back to a
     * handshake header, then to a default.
     */
    private String param(WebSocketSession session, String queryName, String headerName, String fallback) {
        if (session.getUri() != null) {
            MultiValueMapLike query = new MultiValueMapLike(session.getUri().toString());
            String v = query.first(queryName);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        List<String> header = session.getHandshakeHeaders().get(headerName);
        if (header != null && !header.isEmpty() && !header.get(0).isBlank()) {
            return header.get(0);
        }
        return fallback;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Tiny wrapper so the query parsing above reads cleanly. */
    private static final class MultiValueMapLike {
        private final org.springframework.util.MultiValueMap<String, String> params;

        MultiValueMapLike(String uri) {
            this.params = UriComponentsBuilder.fromUriString(uri).build().getQueryParams();
        }

        String first(String key) {
            return params.getFirst(key);
        }
    }
}
