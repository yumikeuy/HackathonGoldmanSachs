package com.hackathon.alerting.runbooks.matching;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.runbooks.RunbookService;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.matching.RunbookSelector.Selection;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Selects and attaches a runbook to a newly created alert from the pre-compiled {@link RunbookCache}
 * (owner: Role 3).
 *
 * <p>Selection priority per AGENTS.md §6 step 8 and roles.md §8.2:
 * <ol>
 *   <li>Rule's {@code override_runbook_id} (reason {@code "override"})</li>
 *   <li>Alert's team runbook whose condition matches {@code event}, lowest id first (reason {@code "team"})</li>
 *   <li>Global runbook whose condition matches {@code event}, lowest id first (reason {@code "global"})</li>
 * </ol>
 * The order itself lives in {@link RunbookSelector}, shared with {@link RunbookResync}.
 */
@Service
public class DefaultRunbookService implements RunbookService {

    static final String REASON_OVERRIDE = RunbookSelector.REASON_OVERRIDE;
    static final String REASON_TEAM = RunbookSelector.REASON_TEAM;
    static final String REASON_GLOBAL = RunbookSelector.REASON_GLOBAL;

    private final RunbookCache cache;
    private final RunbookRepository runbooks;
    private final AuditService audit;
    private final RunbookSelector selector;

    public DefaultRunbookService(RunbookCache cache, RunbookRepository runbooks, AuditService audit) {
        this.cache = cache;
        this.runbooks = runbooks;
        this.audit = audit;
        this.selector = new RunbookSelector(runbooks);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(Alert alert, JsonNode event) {
        if (alert == null) {
            return;
        }
        Optional<Selection> selection = selector.select(alert, event, cache.current());
        if (selection.isEmpty()) {
            return;
        }
        Selection match = selection.get();
        alert.attachRunbook(runbooks.getReferenceById(match.id()));

        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.put("runbookId", match.id());
        if (match.fileName() != null) {
            details.put("fileName", match.fileName());
        } else {
            details.putNull("fileName");
        }
        details.put("reason", match.reason());
        audit.log(alert.getId(), AuditLog.SYSTEM_ACTOR, AuditActions.RUNBOOK_ATTACHED, details);
    }
}
