package com.hackathon.alerting.rules.repository;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC implementation of {@link AlertJdbcOperations}; Spring Data picks it up as a fragment of
 * {@link AlertRepository} by the {@code Impl} suffix.
 */
class AlertJdbcOperationsImpl implements AlertJdbcOperations {

    /** The conflict target must repeat the predicate of the partial unique index uq_alerts_open_fingerprint. */
    private static final String INSERT_IF_NO_OPEN_ALERT = """
            INSERT INTO alerts (rule_id, team_id, runbook_id, fingerprint, severity, status, title,
                                occurrences_count, sample_payload, first_seen_at, last_seen_at)
            VALUES (:ruleId, :teamId, :runbookId, :fingerprint, :severity, :status, :title,
                    :occurrencesCount, CAST(:samplePayload AS jsonb), :firstSeenAt, :lastSeenAt)
            ON CONFLICT (fingerprint) WHERE status IN ('OPEN', 'ACKED') DO NOTHING
            RETURNING id
            """;

    private static final String ADD_OCCURRENCES = """
            UPDATE alerts
            SET occurrences_count = occurrences_count + ?,
                last_seen_at      = GREATEST(last_seen_at, ?)
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    AlertJdbcOperationsImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public Optional<Long> insertIfNoOpenAlert(Alert alert) {
        Objects.requireNonNull(alert, "alert");
        if (alert.getId() != null || alert.getStatus() != AlertStatus.OPEN) {
            throw new IllegalArgumentException("Expected a new OPEN alert, got " + alert);
        }
        return jdbcClient.sql(INSERT_IF_NO_OPEN_ALERT)
                .param("ruleId", alert.getRule() == null ? null : alert.getRule().getId(), Types.BIGINT)
                .param("teamId", alert.getTeam() == null ? null : alert.getTeam().getId(), Types.BIGINT)
                .param("runbookId", alert.getRunbook() == null ? null : alert.getRunbook().getId(), Types.BIGINT)
                .param("fingerprint", alert.getFingerprint())
                .param("severity", alert.getSeverity().name())
                .param("status", alert.getStatus().name())
                .param("title", alert.getTitle())
                .param("occurrencesCount", alert.getOccurrencesCount())
                .param("samplePayload", alert.getSamplePayload().toString())
                .param("firstSeenAt", utc(alert.getFirstSeenAt()))
                .param("lastSeenAt", utc(alert.getLastSeenAt()))
                .query(Long.class)
                .optional();
    }

    @Override
    public int addOccurrences(Collection<OccurrenceDelta> deltas) {
        if (deltas.isEmpty()) {
            return 0;
        }
        List<OccurrenceDelta> batch = List.copyOf(deltas);
        int[][] counts = jdbcTemplate.batchUpdate(ADD_OCCURRENCES, batch, batch.size(), (ps, delta) -> {
            ps.setInt(1, delta.count());
            ps.setObject(2, utc(delta.lastSeenAt()));
            ps.setLong(3, delta.alertId());
        });
        int updated = 0;
        for (int[] chunk : counts) {
            for (int count : chunk) {
                // With reWriteBatchedInserts the driver may report SUCCESS_NO_INFO (-2) instead of a count
                updated += count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : count;
            }
        }
        return updated;
    }

    /** timestamptz is bound as OffsetDateTime so the JVM time zone never affects the stored instant. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
