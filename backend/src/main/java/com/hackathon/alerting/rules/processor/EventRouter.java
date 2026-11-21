package com.hackathon.alerting.rules.processor;

import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.rules.alert.AlertCreator;
import com.hackathon.alerting.rules.alert.AlertService;
import com.hackathon.alerting.rules.alert.AlertSummary;
import com.hackathon.alerting.rules.cache.RoutingCache;
import com.hackathon.alerting.rules.cache.RuleRoute;
import com.hackathon.alerting.rules.cache.TeamRoute;
import com.hackathon.alerting.rules.dedup.DedupStore;
import com.hackathon.alerting.rules.dedup.FingerprintCalculator;
import com.hackathon.alerting.rules.metrics.ProcessingMetrics;
import com.hackathon.alerting.rules.metrics.ProcessingMetrics.Outcome;
import com.hackathon.alerting.rules.push.AlertPublisher;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.unrouted.SourceHistory;
import com.hackathon.alerting.rules.unrouted.UnroutedEventHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Routes one event through the configuration (AGENTS.md §6 steps 4–12):
 * <ol>
 *   <li>every team whose condition matches (multi-match); none → {@link UnroutedEventHandler};</li>
 *   <li>for each active rule of the team, in priority order: if the event matches {@code resolve_condition},
 *       resolve the group's open alert; otherwise, if it matches {@code condition}, fire the rule;</li>
 *   <li>firing: fingerprint → Redis claim → first of the group creates the alert ({@link AlertCreator}),
 *       the others are counted as repeats.</li>
 * </ol>
 * A team that matches but has no matching rule produces nothing — the event belongs to the team, which
 * chose not to alert on it. Every routed event is also counted in {@link SourceHistory} (which teams a source's
 * events go to), the history behind team suggestions for unrouted alerts.
 */
@Component
public class EventRouter {

    private final RoutingCache routing;
    private final FingerprintCalculator fingerprints;
    private final DedupStore dedup;
    private final AlertCreator creator;
    private final AlertService alertService;
    private final AlertRepository alerts;
    private final AlertPublisher publisher;
    private final UnroutedEventHandler unrouted;
    private final SourceHistory sourceHistory;
    private final ProcessingMetrics metrics;

    public EventRouter(RoutingCache routing, FingerprintCalculator fingerprints, DedupStore dedup, AlertCreator creator,
                       AlertService alertService, AlertRepository alerts, AlertPublisher publisher,
                       UnroutedEventHandler unrouted, SourceHistory sourceHistory, ProcessingMetrics metrics) {
        this.routing = routing;
        this.fingerprints = fingerprints;
        this.dedup = dedup;
        this.creator = creator;
        this.alertService = alertService;
        this.alerts = alerts;
        this.publisher = publisher;
        this.unrouted = unrouted;
        this.sourceHistory = sourceHistory;
        this.metrics = metrics;
    }

    public void route(EventEnvelope envelope) {
        JsonNode event = envelope.rawPayload();
        List<TeamRoute> teams = routing.current().teamsMatching(event);
        if (teams.isEmpty()) {
            unrouted.handle(envelope);
            metrics.eventProcessed(Outcome.UNROUTED);
            return;
        }
        sourceHistory.record(envelope.sourceName(), teams);
        metrics.eventProcessed(Outcome.ROUTED);
        for (TeamRoute team : teams) {
            for (RuleRoute rule : team.rules()) {
                if (rule.resolves(event)) {
                    alertService.autoResolve(rule, fingerprints.forRule(rule, event, envelope.ingestedAt()), envelope.ingestedAt());
                } else if (rule.fires(event)) {
                    fire(rule, envelope);
                }
            }
        }
    }

    /** Creates the group's alert or counts a repeat (AGENTS.md §6 step 7). */
    private void fire(RuleRoute rule, EventEnvelope envelope) {
        String fingerprint = fingerprints.forRule(rule, envelope.rawPayload(), envelope.ingestedAt());
        int window = rule.rule().getDedupWindowSec();
        if (!dedup.tryClaim(fingerprint, window)) {
            dedup.countRepeat(fingerprint, window);
            metrics.repeat();
            return;
        }
        Optional<AlertSummary> created;
        try {
            created = creator.create(rule, envelope, fingerprint);
        } catch (RuntimeException e) {
            dedup.releaseClaim(fingerprint);
            throw e;
        }
        if (created.isPresent()) {
            dedup.bindAlert(fingerprint, created.get().id());
            metrics.alertCreated(created.get().severity());
            publisher.publish(created.get());
            return;
        }
        // Redis forgot the group but Postgres still has its open alert: re-bind and count this event as a repeat.
        alerts.findOpenByFingerprint(fingerprint).ifPresentOrElse(
                open -> {
                    dedup.bindAlert(fingerprint, open.getId());
                    dedup.countRepeat(fingerprint, window);
                    metrics.repeat();
                },
                () -> dedup.releaseClaim(fingerprint));
    }
}
