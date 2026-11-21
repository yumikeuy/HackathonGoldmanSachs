package com.hackathon.alerting.rules.api;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.ingest.recent.RecentEvents;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Previews a condition against the last {@value RecentEvents#CAPACITY} events, for the condition builder.
 * Nothing is stored and nothing is alerted.
 */
@Service
public class DryRunService {

    static final int DEFAULT_SAMPLE_SIZE = 10;

    private final RecentEvents recentEvents;
    private final TeamRepository teams;
    private final ConditionCompiler compiler;

    public DryRunService(RecentEvents recentEvents, TeamRepository teams, ConditionCompiler compiler) {
        this.recentEvents = recentEvents;
        this.teams = teams;
        this.compiler = compiler;
    }

    /** @throws InvalidRequestException for an invalid condition or an unknown team */
    public DryRunResult run(DryRunRequest request) {
        boolean strict = Objects.requireNonNullElse(request.strict(), false);
        Predicate<JsonNode> belongs = event -> true;
        if (request.teamId() != null) {
            Team team = teams.findById(request.teamId())
                    .orElseThrow(() -> new InvalidRequestException("Team " + request.teamId() + " does not exist"));
            strict = team.isStrict();
            // Same semantics as routing: a team without a condition owns no events.
            belongs = team.getCondition() == null || team.getCondition().isNull()
                    ? event -> false
                    : compiler.compile(team.getCondition(), strict);
        }
        Predicate<JsonNode> condition = compiler.compile(request.condition(), strict);
        int sampleSize = Objects.requireNonNullElse(request.sampleSize(), DEFAULT_SAMPLE_SIZE);

        int checked = 0;
        int matched = 0;
        List<JsonNode> samples = new ArrayList<>();
        for (JsonNode event : recentEvents.latest(RecentEvents.CAPACITY)) {
            if (!belongs.test(event)) {
                continue;
            }
            checked++;
            if (condition.test(event)) {
                matched++;
                if (samples.size() < sampleSize) {
                    samples.add(event);
                }
            }
        }
        return new DryRunResult(checked, matched, samples);
    }
}
