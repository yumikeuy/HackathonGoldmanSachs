package com.hackathon.alerting.rules.repository;

import java.time.Instant;
import java.util.Objects;

/**
 * Repeats of one alert accumulated in Redis ({@code cnt:{fp}}) since the previous flush.
 *
 * @param alertId    alert to update (the value of {@code dedup:{fp}})
 * @param count      number of repeats to add; positive
 * @param lastSeenAt time of the latest repeat
 */
public record OccurrenceDelta(long alertId, int count, Instant lastSeenAt) {

    public OccurrenceDelta {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }
}
