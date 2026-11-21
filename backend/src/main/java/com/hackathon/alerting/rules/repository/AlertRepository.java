package com.hackathon.alerting.rules.repository;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Access to {@code alerts}.
 *
 * <p>Hot-path creation and counter updates go through {@link AlertJdbcOperations}; everything
 * else (feed, ack/resolve, cleanup) is regular JPA.
 */
public interface AlertRepository extends JpaRepository<Alert, Long>, AlertJdbcOperations {

    /** Statuses covered by the partial unique index: at most one such alert per fingerprint. */
    Set<AlertStatus> OPEN_STATUSES = EnumSet.of(AlertStatus.OPEN, AlertStatus.ACKED);

    /** The open (OPEN/ACKED) alert with this fingerprint, if any. Served by {@code uq_alerts_open_fingerprint}. */
    default Optional<Alert> findOpenByFingerprint(String fingerprint) {
        return findByFingerprintAndStatusIn(fingerprint, OPEN_STATUSES);
    }

    /** Prefer {@link #findOpenByFingerprint}; with other statuses the result may not be unique. */
    Optional<Alert> findByFingerprintAndStatusIn(String fingerprint, Collection<AlertStatus> statuses);

    /**
     * Keyset page of a team's feed, most recently seen first ({@code GET /api/alerts}).
     * The sort matches index {@code idx_alerts_feed (team_id, last_seen_at DESC, id)}.
     * The rule is fetched in the same query (feed rows show its name).
     *
     * @param position {@code ScrollPosition.keyset()} for the first page, then {@code window.positionAt(last)}
     * @param limit    page size (AGENTS.md: 50)
     */
    @EntityGraph(attributePaths = "rule")
    Window<Alert> findByTeamIdOrderByLastSeenAtDescIdAsc(Long teamId, ScrollPosition position, Limit limit);

    /** Same as {@link #findByTeamIdOrderByLastSeenAtDescIdAsc}, restricted to the given statuses. */
    @EntityGraph(attributePaths = "rule")
    Window<Alert> findByTeamIdAndStatusInOrderByLastSeenAtDescIdAsc(Long teamId, Collection<AlertStatus> statuses,
                                                                     ScrollPosition position, Limit limit);

    /** Keyset page of UNROUTED alerts (no team), most recently seen first. */
    Window<Alert> findByTeamIsNullOrderByLastSeenAtDescIdAsc(ScrollPosition position, Limit limit);

    /** Same as {@link #findByTeamIsNullOrderByLastSeenAtDescIdAsc}, restricted to the given statuses. */
    Window<Alert> findByTeamIsNullAndStatusInOrderByLastSeenAtDescIdAsc(Collection<AlertStatus> statuses,
                                                                        ScrollPosition position, Limit limit);

    /** Alerts with their rule fetched, for pushing counter updates of many alerts at once. */
    @EntityGraph(attributePaths = "rule")
    List<Alert> findWithRuleByIdIn(Collection<Long> ids);

    /** One alert with rule and runbook fetched, for the alert card. */
    @EntityGraph(attributePaths = {"rule", "runbook"})
    Optional<Alert> findDetailedById(Long id);

    /**
     * Source names of the events stored for an alert (only UNROUTED alerts have such rows), oldest first.
     * Reads table {@code events} (Role 1) read-only; used to look up an unrouted alert's source.
     */
    @Query("select e.sourceName from Event e where e.alert.id = :alertId order by e.ingestedAt asc")
    List<String> findEventSourceNames(Long alertId, Pageable page);

    /** Whether any alert (in any status) references the rule — such a rule cannot be deleted. */
    boolean existsByRuleId(Long ruleId);

    /**
     * Hourly cleanup: deletes RESOLVED alerts resolved before {@code cutoff} (AGENTS.md: older than 7 days).
     * Their outbox rows and audit records go with them (ON DELETE CASCADE); events keep a NULL link.
     *
     * @return number of deleted alerts
     */
    @Modifying
    @Query(value = "DELETE FROM alerts WHERE status = 'RESOLVED' AND resolved_at < :cutoff", nativeQuery = true)
    int deleteResolvedBefore(Instant cutoff);
}
