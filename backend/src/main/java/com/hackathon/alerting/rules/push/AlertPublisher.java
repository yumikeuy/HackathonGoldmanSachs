package com.hackathon.alerting.rules.push;

import com.hackathon.alerting.common.config.WebSocketTopics;
import com.hackathon.alerting.rules.alert.AlertSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Pushes alert changes to the UI: {@code /topic/alerts/{teamId}}, or {@code /topic/unrouted} for alerts
 * without a team (AGENTS.md §6 step 10).
 *
 * <p>Counter updates go through {@link #publishThrottled}: at most one message per alert per second across
 * all instances ({@code SET ws:{alert_id} NX PX 1000}), so a burst of 500 events does not flood browsers.
 * Creation and status changes use {@link #publish}, which always sends (and restarts the throttle window).
 *
 * <p>Call only after the change has committed. Push failures are logged, never thrown: the UI can always
 * re-read the feed, while failing here would roll back or retry event processing.
 */
@Component
public class AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertPublisher.class);
    private static final Duration THROTTLE = Duration.ofSeconds(1);

    private final SimpMessagingTemplate messaging;
    private final StringRedisTemplate redis;

    public AlertPublisher(SimpMessagingTemplate messaging, StringRedisTemplate redis) {
        this.messaging = messaging;
        this.redis = redis;
    }

    /** Sends now: new alert, ack, resolve. */
    public void publish(AlertSummary alert) {
        try {
            redis.opsForValue().set(throttleKey(alert.id()), "1", THROTTLE);
        } catch (RuntimeException e) {
            log.debug("Could not set push throttle for alert {}", alert.id(), e);
        }
        send(alert);
    }

    /** Sends unless this alert was pushed during the last second: counter updates. */
    public void publishThrottled(AlertSummary alert) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(throttleKey(alert.id()), "1", THROTTLE))) {
                return;
            }
        } catch (RuntimeException e) {
            log.debug("Push throttle unavailable for alert {}, sending anyway", alert.id(), e);
        }
        send(alert);
    }

    private void send(AlertSummary alert) {
        String destination = alert.teamId() == null ? WebSocketTopics.UNROUTED : WebSocketTopics.alerts(alert.teamId());
        try {
            messaging.convertAndSend(destination, alert);
        } catch (RuntimeException e) {
            log.warn("Failed to push alert {} to {}", alert.id(), destination, e);
        }
    }

    private static String throttleKey(long alertId) {
        return "ws:" + alertId;
    }
}
