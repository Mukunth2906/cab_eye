package com.cabeye.backend.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bridges application real-time events, presence, and distributed caches with Redis.
 *
 * <p>When Redis is not configured (e.g. standalone laptop development with H2),
 * this bridge safely degrades into a no-op / local fallback, ensuring zero external
 * dependency for local dev and tests.
 */
@Component
public class RedisBridge {

    private static final Logger log = LoggerFactory.getLogger(RedisBridge.class);

    public static final String CHANNEL_RIDE_EVENTS = "cabeye:ride:events";
    private static final String KEY_DRIVER_LOCATIONS = "cabeye:driver:locations";
    private static final String KEY_RIDE_COUNTER = "cabeye:ride:counter";
    private static final String KEY_OTP_PREFIX = "cabeye:otp:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RedisBridge(@Autowired(required = false) StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper,
                       @Value("${cabeye.redis.enabled:false}") boolean enabledProperty,
                       @Value("${spring.data.redis.host:}") String redisHost) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // Enabled if explicitly set or if a non-empty redis host is provided and redisTemplate exists
        this.enabled = (enabledProperty || (redisHost != null && !redisHost.isBlank())) && redisTemplate != null;

        if (this.enabled) {
            log.info("REDIS bridge ACTIVE (channel={}, host={})", CHANNEL_RIDE_EVENTS, redisHost);
        } else {
            log.info("REDIS bridge INACTIVE — running in standalone local mode");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Publishes a ride event to all connected cluster nodes.
     */
    public void publish(ClusterMessage message) {
        if (!enabled || redisTemplate == null) return;
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL_RIDE_EVENTS, json);
        } catch (Exception e) {
            log.warn("Failed to publish cluster message for ride={}: {}", message.getRideId(), e.getMessage());
        }
    }

    /**
     * Caches the driver's latest GPS ping in Redis, avoiding high-frequency relational DB writes.
     */
    public void recordDriverLocation(String driverId, String locationJson) {
        if (!enabled || redisTemplate == null || driverId == null) return;
        try {
            redisTemplate.opsForHash().put(KEY_DRIVER_LOCATIONS, driverId, locationJson);
        } catch (Exception e) {
            log.debug("Redis driver location cache failure: {}", e.getMessage());
        }
    }

    public String getDriverLocation(String driverId) {
        if (!enabled || redisTemplate == null || driverId == null) return null;
        try {
            Object loc = redisTemplate.opsForHash().get(KEY_DRIVER_LOCATIONS, driverId);
            return loc != null ? loc.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Obtains an atomically incremented ride counter across the cluster.
     */
    public Long incrementRideCounter() {
        if (!enabled || redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().increment(KEY_RIDE_COUNTER);
        } catch (Exception e) {
            log.warn("Failed to increment cluster ride counter: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Caches OTP for multi-instance verification.
     */
    public void saveOtp(String phone, String code, long ttlSeconds) {
        if (!enabled || redisTemplate == null || phone == null) return;
        try {
            redisTemplate.opsForValue().set(KEY_OTP_PREFIX + phone, code, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("Redis save OTP failure: {}", e.getMessage());
        }
    }

    public String getOtp(String phone) {
        if (!enabled || redisTemplate == null || phone == null) return null;
        try {
            return redisTemplate.opsForValue().get(KEY_OTP_PREFIX + phone);
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteOtp(String phone) {
        if (!enabled || redisTemplate == null || phone == null) return;
        try {
            redisTemplate.delete(KEY_OTP_PREFIX + phone);
        } catch (Exception e) {
            log.debug("Redis delete OTP failure: {}", e.getMessage());
        }
    }

    /**
     * Simple ping to check if Redis connection is healthy for readiness probes.
     */
    public boolean ping() {
        if (!enabled || redisTemplate == null) return true; // not failing readiness if not used
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("Redis ping failed: {}", e.getMessage());
            return false;
        }
    }
}
