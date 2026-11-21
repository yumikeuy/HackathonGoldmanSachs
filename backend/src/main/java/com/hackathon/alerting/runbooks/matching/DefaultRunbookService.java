package com.hackathon.alerting.runbooks.matching;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.RunbookService;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.cache.CompiledRunbook;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.cache.RunbookCatalog;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
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
 */
@Service
public class DefaultRunbookService implements RunbookService {

    static final String REASON_OVERRIDE = "override";
    static final String REASON_TEAM = "team";
    static final String REASON_GLOBAL = "global";

    private final RunbookCache cache;
    private final RunbookRepository runbooks;
    private final AuditService audit;

    public DefaultRunbookService(RunbookCache cache, RunbookRepository runbooks, AuditService audit) {
        this.cache = cache;
        this.runbooks = runbooks;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void attach(Alert alert, JsonNode event) {
        if (alert == null) {
            return;
        }
        Optional<Selection> selection = select(alert, event);
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

    private Optional<Selection> select(Alert alert, JsonNode event) {
        RunbookCatalog catalog = cache.current();

        Rule rule = alert.getRule();
        if (rule != null && rule.getOverrideRunbook() != null && rule.getOverrideRunbook().getId() != null) {
            Runbook override = rule.getOverrideRunbook();
            String fileName = resolveOverrideFileName(catalog, override);
            return Optional.of(new Selection(override.getId(), fileName, REASON_OVERRIDE));
        }

        if (alert.getTeam() != null && alert.getTeam().getId() != null) {
            Optional<CompiledRunbook> teamMatch = catalog.firstMatchingTeam(alert.getTeam().getId(), event);
            if (teamMatch.isPresent()) {
                return Optional.of(Selection.from(teamMatch.get(), REASON_TEAM));
            }
        }

        return catalog.firstMatchingGlobal(event)
                .map(rb -> Selection.from(rb, REASON_GLOBAL));
    }

    private String resolveOverrideFileName(RunbookCatalog catalog, Runbook override) {
        Long id = override.getId();
        Optional<CompiledRunbook> cached = catalog.findById(id);
        if (cached.isPresent()) {
            return cached.get().fileName();
        }
        Optional<String> fromDb = runbooks.findSummaryById(id)
                .map(RunbookSummary::fileName);
        if (fromDb.isPresent()) {
            return fromDb.get();
        }
        return Hibernate.isInitialized(override) ? override.getFileName() : null;
    }

    private record Selection(long id, String fileName, String reason) {
        private static Selection from(CompiledRunbook runbook, String reason) {
            return new Selection(runbook.id(), runbook.fileName(), reason);
        }
    }
}
