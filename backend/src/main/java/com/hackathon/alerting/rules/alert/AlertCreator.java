package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.common.json.TemplateRenderer;
import com.hackathon.alerting.delivery.outbox.OutboxService;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.rules.cache.RuleRoute;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.runbooks.RunbookService;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.model.AuditLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;

/**
 * Creates the alert for the first event of a group — the single transaction of AGENTS.md §6 steps 7–9:
 * insert (guarded by the open-fingerprint unique index) → {@link RunbookService#attach} →
 * {@link OutboxService#enqueue} → audit. Either all of it commits or none of it.
 *
 * <p>Redis bookkeeping and the WebSocket push are the caller's job, after this method returned (= committed).
 */
@Component
public class AlertCreator {

    /** Audit action written when an alert is opened. */
    public static final String AUDIT_ALERT_CREATED = "ALERT_CREATED";

    private final AlertRepository alerts;
    private final TemplateRenderer templates;
    private final RunbookService runbooks;
    private final OutboxService outbox;
    private final AuditService audit;

    public AlertCreator(AlertRepository alerts, TemplateRenderer templates, RunbookService runbooks,
                        OutboxService outbox, AuditService audit) {
        this.alerts = alerts;
        this.templates = templates;
        this.runbooks = runbooks;
        this.outbox = outbox;
        this.audit = audit;
    }

    /**
     * @param route       the matched rule
     * @param envelope    the event that opens the group
     * @param fingerprint the group's fingerprint
     * @return the new alert, or empty if an open alert with this fingerprint already exists
     *         (Redis lost the dedup key) — the caller then counts the event as a repeat
     */
    @Transactional
    public Optional<AlertSummary> create(RuleRoute route, EventEnvelope envelope, String fingerprint) {
        Rule rule = route.rule();
        Alert draft = Alert.forRule(rule, fingerprint, title(rule, envelope), envelope.rawPayload(), envelope.ingestedAt());
        Optional<Long> id = alerts.insertIfNoOpenAlert(draft);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Alert alert = alerts.findById(id.get()).orElseThrow();
        runbooks.attach(alert, envelope.rawPayload());
        outbox.enqueue(alert, rule);
        audit.log(alert.getId(), AuditLog.ruleActor(rule.getId()), AUDIT_ALERT_CREATED,
                JsonNodeFactory.instance.objectNode()
                        .put("eventId", envelope.eventId().toString())
                        .put("source", envelope.sourceName()));
        return Optional.of(AlertSummary.from(alert));
    }

    /** {@code presentation.title} rendered against the event; the rule name if there is no template or it renders blank. */
    private String title(Rule rule, EventEnvelope envelope) {
        String template = rule.getPresentation().title();
        if (template == null || template.isBlank()) {
            return rule.getName();
        }
        String rendered = templates.render(template, envelope.rawPayload());
        return rendered.isBlank() ? rule.getName() : rendered;
    }
}
