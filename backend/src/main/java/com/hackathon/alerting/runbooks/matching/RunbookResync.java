package com.hackathon.alerting.runbooks.matching;

import com.hackathon.alerting.rules.alert.AlertSummary;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.push.AlertPublisher;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.cache.RunbookCatalog;
import com.hackathon.alerting.runbooks.matching.RunbookSelector.Selection;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the runbook of <b>open</b> alerts in step with the runbooks: after a runbook is created, edited or deleted, or
 * a rule's "always attach" runbook changes ({@link RunbookSelectionChanged}), every OPEN / ACKED rule alert gets the
 * runbook that would be picked now — same order as for a new alert ({@link RunbookSelector}), matched against the
 * event that opened the alert. A new runbook can therefore replace an older pick, an edited condition can move an
 * alert to another runbook, and a deleted runbook is replaced by the next match or removed.
 *
 * <ul>
 *   <li>Runs in the transaction that made the change, so alert and runbook change commit (or roll back) together; it
 *       selects from a fresh catalog ({@link RunbookCache#load()}) that already sees that change.</li>
 *   <li>Every change is audited — {@code RUNBOOK_ATTACHED} (new or replaced runbook, with the previous one) or
 *       {@code RUNBOOK_DETACHED} — by {@code system}, and pushed to the UI after commit.</li>
 *   <li>Resolved alerts keep the runbook they had: they record what the team used. UNROUTED alerts never get one.</li>
 * </ul>
 */
@Service
public class RunbookResync {

    private static final Logger log = LoggerFactory.getLogger(RunbookResync.class);

    private final RunbookCache cache;
    private final AlertRepository alerts;
    private final RunbookRepository runbooks;
    private final AuditService audit;
    private final AlertPublisher publisher;
    private final EntityManager entityManager;
    private final RunbookSelector selector;

    public RunbookResync(RunbookCache cache, AlertRepository alerts, RunbookRepository runbooks, AuditService audit,
                         AlertPublisher publisher, EntityManager entityManager) {
        this.cache = cache;
        this.alerts = alerts;
        this.runbooks = runbooks;
        this.audit = audit;
        this.publisher = publisher;
        this.entityManager = entityManager;
        this.selector = new RunbookSelector(runbooks);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSelectionChanged(RunbookSelectionChanged change) {
        resyncOpenAlerts(change);
    }

    /**
     * Re-selects the runbook of every open rule alert.
     *
     * @return how many alerts changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int resyncOpenAlerts(RunbookSelectionChanged change) {
        entityManager.flush();   // the caller's change must be visible to the queries below
        RunbookCatalog catalog = cache.load();
        List<AlertSummary> changed = new ArrayList<>();
        for (Alert alert : alerts.findByStatusInAndRuleIsNotNull(AlertRepository.OPEN_STATUSES)) {
            Long previous = alert.getRunbook() == null ? null : alert.getRunbook().getId();
            Optional<Selection> selection = selector.select(alert, alert.getSamplePayload(), catalog, change.removedRunbookId());
            Long next = selection.map(Selection::id).orElse(null);
            if (Objects.equals(previous, next)) {
                continue;
            }
            ObjectNode details = JsonNodeFactory.instance.objectNode().put("trigger", change.trigger());
            if (previous != null) {
                details.put("previousRunbookId", previous);
            } else {
                details.putNull("previousRunbookId");
            }
            if (selection.isPresent()) {
                Selection match = selection.get();
                alert.attachRunbook(runbooks.getReferenceById(match.id()));
                details.put("runbookId", match.id()).put("fileName", match.fileName()).put("reason", match.reason());
                audit.log(alert.getId(), AuditLog.SYSTEM_ACTOR, AuditActions.RUNBOOK_ATTACHED, details);
            } else {
                alert.detachRunbook();
                audit.log(alert.getId(), AuditLog.SYSTEM_ACTOR, AuditActions.RUNBOOK_DETACHED, details);
            }
            changed.add(AlertSummary.from(alert));
        }
        if (!changed.isEmpty()) {
            log.info("Runbook resync ({}): {} open alert(s) changed", change.trigger(), changed.size());
            afterCommit(() -> changed.forEach(publisher::publish));
        }
        return changed.size();
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
