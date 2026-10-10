package com.cabeye.backend.redis;

import com.cabeye.backend.websocket.RideSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listens on Redis topic "cabeye:ride:events" and routes incoming cluster events
 * to locally connected WebSocket clients.
 */
@Component
public class RedisRideEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisRideEventSubscriber.class);

    private final RideSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public RedisRideEventSubscriber(@Lazy RideSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            ClusterMessage clusterMsg = objectMapper.readValue(payload, ClusterMessage.class);
            sessionManager.receiveClusterMessage(clusterMsg);
        } catch (Exception e) {
            log.warn("Failed to process inbound Redis cluster message: {}", e.getMessage());
        }
    }
}
