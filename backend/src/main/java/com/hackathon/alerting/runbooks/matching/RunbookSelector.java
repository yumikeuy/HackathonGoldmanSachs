package com.hackathon.alerting.runbooks.matching;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.cache.CompiledRunbook;
import com.hackathon.alerting.runbooks.cache.RunbookCatalog;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.hibernate.Hibernate;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * The runbook selection order (AGENTS.md §6 step 8), shared by attaching a runbook to a new alert
 * ({@link DefaultRunbookService}) and re-selecting it for open alerts after a runbook or rule changed
 * ({@link RunbookResync}), so both always pick the same way:
 * <ol>
 *   <li>the rule's {@code override_runbook_id} (reason {@value #REASON_OVERRIDE});</li>
 *   <li>the alert team's runbook whose condition matches the event, lowest id first (reason {@value #REASON_TEAM});</li>
 *   <li>a global runbook whose condition matches the event, lowest id first (reason {@value #REASON_GLOBAL}).</li>
 * </ol>
 * Stateless apart from the repository used to name an override that is missing from the catalog.
 */
final class RunbookSelector {

    static final String REASON_OVERRIDE = "override";
    static final String REASON_TEAM = "team";
    static final String REASON_GLOBAL = "global";

    private final RunbookRepository runbooks;

    RunbookSelector(RunbookRepository runbooks) {
        this.runbooks = Objects.requireNonNull(runbooks, "runbooks");
    }

    /**
     * @param alert   the alert (its rule and team are read)
     * @param event   the event to match conditions against — the event that opened the alert
     * @param catalog the runbooks to choose from
     * @return the runbook to attach, or empty if none applies
     */
    Optional<Selection> select(Alert alert, JsonNode event, RunbookCatalog catalog) {
        return select(alert, event, catalog, null);
    }

    /**
     * Same as {@link #select(Alert, JsonNode, RunbookCatalog)}, as if runbook {@code removedRunbookId} were already
     * deleted: it is neither a rule's override nor a candidate. Used just before a runbook is deleted.
     */
    Optional<Selection> select(Alert alert, JsonNode event, RunbookCatalog catalog, Long removedRunbookId) {
        if (removedRunbookId != null) {
            catalog = catalog.without(removedRunbookId);
        }
        Rule rule = alert.getRule();
        if (rule != null && rule.getOverrideRunbook() != null && rule.getOverrideRunbook().getId() != null
                && !rule.getOverrideRunbook().getId().equals(removedRunbookId)) {
            Runbook override = rule.getOverrideRunbook();
            return Optional.of(new Selection(override.getId(), overrideFileName(catalog, override), REASON_OVERRIDE));
        }
        if (alert.getTeam() != null && alert.getTeam().getId() != null) {
            Optional<CompiledRunbook> teamMatch = catalog.firstMatchingTeam(alert.getTeam().getId(), event);
            if (teamMatch.isPresent()) {
                return Optional.of(Selection.from(teamMatch.get(), REASON_TEAM));
            }
        }
        return catalog.firstMatchingGlobal(event).map(runbook -> Selection.from(runbook, REASON_GLOBAL));
    }

    private String overrideFileName(RunbookCatalog catalog, Runbook override) {
        Long id = override.getId();
        Optional<CompiledRunbook> cached = catalog.findById(id);
        if (cached.isPresent()) {
            return cached.get().fileName();
        }
        Optional<String> fromDb = runbooks.findSummaryById(id).map(RunbookSummary::fileName);
        if (fromDb.isPresent()) {
            return fromDb.get();
        }
        return Hibernate.isInitialized(override) ? override.getFileName() : null;
    }

    /**
     * The chosen runbook.
     *
     * @param reason {@value #REASON_OVERRIDE}, {@value #REASON_TEAM} or {@value #REASON_GLOBAL}
     */
    record Selection(long id, String fileName, String reason) {

        static Selection from(CompiledRunbook runbook, String reason) {
            return new Selection(runbook.id(), runbook.fileName(), reason);
        }
    }
}
