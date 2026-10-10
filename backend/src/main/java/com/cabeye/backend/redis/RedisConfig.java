package com.cabeye.backend.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Spring configuration for Redis Pub/Sub messaging.
 * Active only when {@code cabeye.redis.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "cabeye.redis.enabled", havingValue = "true")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisRideEventSubscriber subscriber) {
        log.info("Configuring Redis Pub/Sub listener on channel: {}", RedisBridge.CHANNEL_RIDE_EVENTS);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisBridge.CHANNEL_RIDE_EVENTS));
        return container;
    }
}
