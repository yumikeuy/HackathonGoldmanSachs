package com.hackathon.alerting.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Subscribes to the Redis channel {@value RedisConfigChangedPublisher#CHANNEL} and re-publishes every message as a
 * local {@link ConfigChangedEvent}, so caches on every instance reload.
 *
 * <p>Redis is not required to start: the subscription is made in the background and retried every
 * {@link #RETRY_INTERVAL} until Redis is reachable; afterwards the container itself recovers lost connections.
 */
@Component
public class ConfigChangedSubscriber implements SmartLifecycle {

    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(ConfigChangedSubscriber.class);

    private final RedisConnectionFactory connectionFactory;
    private final ApplicationEventPublisher events;
    private volatile RedisMessageListenerContainer container;
    private volatile boolean running;
    private Thread starter;

    public ConfigChangedSubscriber(RedisConnectionFactory connectionFactory, ApplicationEventPublisher events) {
        this.connectionFactory = connectionFactory;
        this.events = events;
    }

    @Override
    public synchronized void start() {
        running = true;
        starter = Thread.ofVirtual().name("config-changed-subscriber").start(this::subscribeUntilConnected);
    }

    private void subscribeUntilConnected() {
        while (running) {
            RedisMessageListenerContainer attempt = newContainer();
            try {
                attempt.start();
                container = attempt;
                log.info("Subscribed to Redis channel {}", RedisConfigChangedPublisher.CHANNEL);
                return;
            } catch (RuntimeException e) {
                destroyQuietly(attempt);
                log.warn("Redis unavailable, config changes from other instances are not received yet: {}", e.getMessage());
                try {
                    Thread.sleep(RETRY_INTERVAL);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (starter != null) {
            starter.interrupt();
        }
        if (container != null) {
            destroyQuietly(container);
            container = null;
        }
    }

    /** A fresh container per attempt: a container whose first subscription failed is not reused. */
    private RedisMessageListenerContainer newContainer() {
        RedisMessageListenerContainer attempt = new RedisMessageListenerContainer();
        attempt.setConnectionFactory(connectionFactory);
        attempt.addMessageListener(
                (message, pattern) -> events.publishEvent(new ConfigChangedEvent(new String(message.getBody(), StandardCharsets.UTF_8))),
                new ChannelTopic(RedisConfigChangedPublisher.CHANNEL));
        attempt.afterPropertiesSet();
        return attempt;
    }

    private static void destroyQuietly(RedisMessageListenerContainer container) {
        try {
            container.destroy();
        } catch (Exception e) {
            log.debug("Error while closing a Redis listener container", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
