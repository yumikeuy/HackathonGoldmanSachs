package com.hackathon.alerting.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

/**
 * A Redis Pub/Sub subscription that does not require Redis at startup: it subscribes in the background and retries
 * every {@link #RETRY_INTERVAL} until Redis is reachable; afterwards the container itself recovers lost connections.
 * A fresh container is used per attempt, since one whose first subscription failed is not reused.
 */
final class RedisChannelSubscription {

    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(RedisChannelSubscription.class);

    private final RedisConnectionFactory connectionFactory;
    private final String channel;
    private final MessageListener listener;
    private volatile RedisMessageListenerContainer container;
    private volatile boolean running;
    private Thread starter;

    RedisChannelSubscription(RedisConnectionFactory connectionFactory, String channel, MessageListener listener) {
        this.connectionFactory = connectionFactory;
        this.channel = channel;
        this.listener = listener;
    }

    synchronized void start() {
        running = true;
        starter = Thread.ofVirtual().name("redis-subscriber-" + channel).start(this::subscribeUntilConnected);
    }

    synchronized void stop() {
        running = false;
        if (starter != null) {
            starter.interrupt();
        }
        if (container != null) {
            destroyQuietly(container);
            container = null;
        }
    }

    boolean isRunning() {
        return running;
    }

    private void subscribeUntilConnected() {
        while (running) {
            RedisMessageListenerContainer attempt = new RedisMessageListenerContainer();
            attempt.setConnectionFactory(connectionFactory);
            attempt.addMessageListener(listener, new ChannelTopic(channel));
            attempt.afterPropertiesSet();
            try {
                attempt.start();
                container = attempt;
                log.info("Subscribed to Redis channel {}", channel);
                return;
            } catch (RuntimeException e) {
                destroyQuietly(attempt);
                log.warn("Redis unavailable, not subscribed to {} yet: {}", channel, e.getMessage());
                try {
                    Thread.sleep(RETRY_INTERVAL);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void destroyQuietly(RedisMessageListenerContainer container) {
        try {
            container.destroy();
        } catch (Exception e) {
            log.debug("Error while closing a Redis listener container", e);
        }
    }
}
