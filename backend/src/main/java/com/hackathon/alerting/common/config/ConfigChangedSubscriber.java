package com.hackathon.alerting.common.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to the Redis channel {@value RedisConfigChangedPublisher#CHANNEL} and re-publishes every message as a
 * local {@link ConfigChangedEvent}, so caches on every instance reload. Redis is not required to start
 * ({@link RedisChannelSubscription}).
 */
@Component
public class ConfigChangedSubscriber implements SmartLifecycle {

    private final RedisChannelSubscription subscription;

    public ConfigChangedSubscriber(RedisConnectionFactory connectionFactory, ApplicationEventPublisher events) {
        this.subscription = new RedisChannelSubscription(connectionFactory, RedisConfigChangedPublisher.CHANNEL,
                (message, pattern) -> events.publishEvent(new ConfigChangedEvent(new String(message.getBody(), StandardCharsets.UTF_8))));
    }

    @Override
    public void start() {
        subscription.start();
    }

    @Override
    public void stop() {
        subscription.stop();
    }

    @Override
    public boolean isRunning() {
        return subscription.isRunning();
    }
}
