package com.hackathon.alerting.common.config;

import java.util.Objects;

/**
 * Local Spring application event: cached configuration of kind {@link #what()} changed and must be reloaded.
 *
 * <p>Caches subscribe with {@code @EventListener} and reload from the database:
 * <pre>{@code
 * @EventListener
 * void onConfigChanged(ConfigChangedEvent event) {
 *     if (event.is(ConfigKinds.RULES) || event.is(ConfigKinds.TEAMS)) reload();
 * }
 * }</pre>
 * Every instance receives it — in production it is re-published from the Redis channel {@code config.changed}.
 *
 * @param what one of {@link ConfigKinds}
 */
public record ConfigChangedEvent(String what) {

    public ConfigChangedEvent {
        Objects.requireNonNull(what, "what");
    }

    public boolean is(String kind) {
        return what.equals(kind);
    }
}
