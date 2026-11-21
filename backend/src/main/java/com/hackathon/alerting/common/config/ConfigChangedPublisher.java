package com.hackathon.alerting.common.config;

/**
 * Contract (AGENTS.md §8, owner Role 1): announce that configuration changed so that every instance
 * reloads its caches. Called by CRUD services after saving teams, users, rules, runbooks or sources.
 *
 * <p>Guarantees, which every implementation must keep:
 * <ul>
 *   <li>if called inside a transaction, delivery happens <b>after commit</b> (never on rollback),
 *       so listeners always reload committed data;</li>
 *   <li>listeners receive a {@link ConfigChangedEvent} on every instance, including this one.</li>
 * </ul>
 */
public interface ConfigChangedPublisher {

    /** @param what one of {@link ConfigKinds} */
    void publish(String what);
}
