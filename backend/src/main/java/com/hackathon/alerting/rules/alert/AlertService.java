package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.common.web.CursorPage;
import com.hackathon.alerting.common.web.Cursors;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.rules.cache.RuleRoute;
import com.hackathon.alerting.rules.dedup.DedupStore;
import com.hackathon.alerting.rules.metrics.ProcessingMetrics;
import com.hackathon.alerting.rules.metrics.ProcessingMetrics.ResolvedBy;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import com.hackathon.alerting.rules.push.AlertPublisher;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Alert lifecycle and queries: feed, card, acknowledge, resolve and automatic resolution by
 * {@code resolve_condition}.
 *
 * <p>State changes run in a programmatic transaction; Redis cleanup and the WebSocket push happen only after
 * it committed, so the UI never shows a change that was rolled back.
 */
@Service
public class AlertService {

    private final AlertRepository alerts;
    private final AuditService audit;
    private final DedupStore dedup;
    private final AlertPublisher publisher;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final ProcessingMetrics metrics;
    private final Clock clock;

    public AlertService(AlertRepository alerts, AuditService audit, DedupStore dedup, AlertPublisher publisher,
                        TransactionTemplate transactionTemplate, ProcessingMetrics metrics, Clock clock) {
        this.alerts = alerts;
        this.audit = audit;
        this.dedup = dedup;
        this.publisher = publisher;
        this.tx = transactionTemplate;
        this.readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * A page of the feed, most recently seen first.
     *
     * @param teamId   the team, or {@code null} together with {@code unrouted = true}
     * @param unrouted alerts no team matched
     * @param statuses status filter; empty means all
     * @throws InvalidRequestException unless exactly one of {@code teamId} and {@code unrouted} is given
     */
    public CursorPage<AlertSummary> feed(Long teamId, boolean unrouted, Collection<AlertStatus> statuses, String cursor, Integer limit) {
        if ((teamId == null) == !unrouted) {
            throw new InvalidRequestException("Pass exactly one of 'teamId' or 'unrouted=true'");
        }
        ScrollPosition position = Cursors.decode(cursor);
        Limit pageSize = Cursors.limit(limit);
        boolean filtered = statuses != null && !statuses.isEmpty();
        return readOnlyTx.execute(status -> {
            Window<Alert> window;
            if (unrouted) {
                window = filtered
                        ? alerts.findByTeamIsNullAndStatusInOrderByLastSeenAtDescIdAsc(statuses, position, pageSize)
                        : alerts.findByTeamIsNullOrderByLastSeenAtDescIdAsc(position, pageSize);
            } else {
                window = filtered
                        ? alerts.findByTeamIdAndStatusInOrderByLastSeenAtDescIdAsc(teamId, statuses, position, pageSize)
                        : alerts.findByTeamIdOrderByLastSeenAtDescIdAsc(teamId, position, pageSize);
            }
            return CursorPage.of(window, AlertSummary::from);
        });
    }

    /** The alert card. @throws NotFoundException for an unknown id */
    public AlertDetail get(long id) {
        return readOnlyTx.execute(status -> AlertDetail.from(load(id)));
    }

    /**
     * OPEN → ACKED; repeating is a no-op.
     *
     * @throws NotFoundException             for an unknown id
     * @throws AlertAlreadyResolvedException if the alert is resolved
     */
    public AlertDetail acknowledge(long id, String actor) {
        Change change = tx.execute(status -> {
            Alert alert = load(id);
            boolean changed;
            try {
                changed = alert.acknowledge();
            } catch (IllegalStateException e) {
                throw new AlertAlreadyResolvedException(id);
            }
            if (changed) {
                audit.log(id, actor, AuditActions.ACK, null);
            }
            return new Change(AlertDetail.from(alert), changed, alert.getFingerprint());
        });
        if (change.changed()) {
            publisher.publish(change.detail().summary());
        }
        return change.detail();
    }

    /**
     * OPEN/ACKED → RESOLVED; repeating is a no-op. The next matching event opens a new alert.
     *
     * @throws NotFoundException for an unknown id
     */
    public AlertDetail resolve(long id, String actor) {
        Change change = tx.execute(status -> {
            Alert alert = load(id);
            boolean changed = alert.resolve(clock.instant());
            if (changed) {
                audit.log(id, actor, AuditActions.RESOLVE, null);
            }
            return new Change(AlertDetail.from(alert), changed, alert.getFingerprint());
        });
        if (change.changed()) {
            metrics.alertResolved(ResolvedBy.USER);
            afterResolved(change);
        }
        return change.detail();
    }

    /**
     * Resolves the open alert of a group because an event matched the rule's {@code resolve_condition}
     * (AGENTS.md §6 step 11).
     *
     * @param route       the rule whose resolve condition matched
     * @param fingerprint the group, computed from the resolving event
     * @param at          the resolving event's time
     * @return the resolved alert, or empty if the group had no open alert
     */
    public Optional<AlertSummary> autoResolve(RuleRoute route, String fingerprint, Instant at) {
        Change change = tx.execute(status -> alerts.findOpenByFingerprint(fingerprint)
                .map(alert -> {
                    alert.resolve(at);
                    audit.log(alert.getId(), AuditLog.ruleActor(route.id()), AuditActions.AUTO_CLOSE, null);
                    return new Change(AlertDetail.from(alert), true, fingerprint);
                })
                .orElse(null));
        if (change == null) {
            return Optional.empty();
        }
        metrics.alertResolved(ResolvedBy.RULE);
        afterResolved(change);
        return Optional.of(change.detail().summary());
    }

    private void afterResolved(Change change) {
        dedup.forget(change.fingerprint());
        publisher.publish(change.detail().summary());
    }

    private Alert load(long id) {
        return alerts.findDetailedById(id).orElseThrow(() -> new NotFoundException("Alert", id));
    }

    /** Result of a state change, carried out of the transaction. */
    private record Change(AlertDetail detail, boolean changed, String fingerprint) {
    }
}
