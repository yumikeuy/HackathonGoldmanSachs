package com.hackathon.alerting.rules;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.awaitility.Awaitility.await;

/** Test helper for configuration changes in integration tests that run with Redis. */
public final class ConfigChanges {

    private ConfigChanges() {
    }

    /**
     * Announces a configuration change and waits until {@code applied} holds, so events sent afterwards see the new
     * configuration. Reloads arrive asynchronously over Redis Pub/Sub, and right after startup the subscription is
     * still being made in the background, so a first message can be lost: while waiting, the change is re-announced
     * (a reload is idempotent).
     */
    public static void publishAndAwait(ConfigChangedPublisher publisher, String kind, BooleanSupplier applied, Duration timeout) {
        publisher.publish(kind);
        await().atMost(timeout).pollInterval(Duration.ofMillis(250)).until(() -> {
            if (applied.getAsBoolean()) {
                return true;
            }
            publisher.publish(kind);
            return false;
        });
    }
}
