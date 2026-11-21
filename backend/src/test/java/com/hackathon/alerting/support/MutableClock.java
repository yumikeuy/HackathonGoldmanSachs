package com.hackathon.alerting.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} tests can move forward, e.g. past a retry delay. Starts one hour ahead of real time, so rows that
 * entities stamp with {@code Instant.now()} (e.g. {@code delivery_outbox.next_retry_at}) are already due.
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now().plus(Duration.ofHours(1));

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
