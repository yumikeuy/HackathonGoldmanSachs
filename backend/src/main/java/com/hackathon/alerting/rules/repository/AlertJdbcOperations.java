package com.hackathon.alerting.rules.repository;

import com.hackathon.alerting.rules.model.Alert;

import java.util.Collection;
import java.util.Optional;

/**
 * Hot-path writes to {@code alerts} done with plain JDBC.
 *
 * <p>Both methods run immediately on the current transaction's connection: they neither flush
 * nor update the JPA persistence context, so an {@link Alert} entity loaded earlier in the same
 * transaction may hold stale {@code occurrences_count} / {@code last_seen_at} afterwards.
 */
public interface AlertJdbcOperations {

    /**
     * Inserts a new OPEN alert unless an open (OPEN/ACKED) alert with the same fingerprint already
     * exists: {@code INSERT … ON CONFLICT (fingerprint) WHERE status IN ('OPEN','ACKED') DO NOTHING RETURNING id}.
     * This is the database half of deduplication (AGENTS.md §6, step 7).
     *
     * <p>The given entity is used only as a value holder and is not persisted or modified;
     * load the created alert by the returned id when the entity is needed.
     *
     * @param alert a new, unsaved alert built with {@link Alert#forRule} or {@link Alert#unrouted}
     * @return id of the created alert, or empty if an open alert with this fingerprint already exists
     */
    Optional<Long> insertIfNoOpenAlert(Alert alert);

    /**
     * Adds flushed repeat counters in one JDBC batch:
     * {@code occurrences_count += count}, {@code last_seen_at = GREATEST(last_seen_at, lastSeenAt)}.
     * Unknown alert ids (e.g. already purged) are ignored.
     *
     * @return number of alerts actually updated
     */
    int addOccurrences(Collection<OccurrenceDelta> deltas);
}
