package com.hackathon.alerting.rules.unrouted;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.repository.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Suggests teams for an UNROUTED alert ({@code GET /api/alerts/{id}/team-suggestions}), to help triage on the
 * unrouted page.
 *
 * <p>For every team with a condition:
 * <ul>
 *   <li><b>condition share</b> — how many leaves of the team's condition the alert's sample event satisfies, each
 *       leaf checked on its own with the team's strict flag ("3 of 4 conditions match": a near miss);</li>
 *   <li><b>history share</b> — which share of the events from the same source were routed to the team
 *       ({@link SourceHistory}).</li>
 * </ul>
 * {@code score = 0.7 × condition share + 0.3 × history share} when the source has history, otherwise the condition
 * share alone. Teams scoring 0 are left out; at most {@value #MAX_SUGGESTIONS}, best first.
 *
 * <p>Not on the hot path: runs per request and may read teams from the database.
 */
@Service
public class TeamSuggestionService {

    static final int MAX_SUGGESTIONS = 5;
    static final double CONDITION_WEIGHT = 0.7;
    static final double HISTORY_WEIGHT = 0.3;

    private static final Logger log = LoggerFactory.getLogger(TeamSuggestionService.class);

    private final AlertRepository alerts;
    private final TeamRepository teams;
    private final ConditionCompiler compiler;
    private final SourceHistory history;

    public TeamSuggestionService(AlertRepository alerts, TeamRepository teams, ConditionCompiler compiler, SourceHistory history) {
        this.alerts = alerts;
        this.teams = teams;
        this.compiler = compiler;
        this.history = history;
    }

    /**
     * @return suggestions, best first; empty for an alert that already has a team
     * @throws NotFoundException for an unknown alert
     */
    @Transactional(readOnly = true)
    public List<TeamSuggestion> suggest(long alertId) {
        Alert alert = alerts.findById(alertId).orElseThrow(() -> new NotFoundException("Alert", alertId));
        if (alert.getTeam() != null) {
            return List.of();
        }
        String source = alerts.findEventSourceNames(alertId, PageRequest.ofSize(1)).stream().findFirst().orElse(null);
        Map<Long, Long> sourceCounts = source == null ? Map.of() : history.teamCounts(source);
        long sourceTotal = sourceCounts.values().stream().mapToLong(Long::longValue).sum();

        List<TeamSuggestion> suggestions = new ArrayList<>();
        for (Team team : teams.findAllByOrderByIdAsc()) {
            List<JsonNode> leaves = ConditionLeaves.of(team.getCondition());
            if (leaves.isEmpty()) {
                continue;   // a team without a condition owns no events (AGENTS.md §11 q.1)
            }
            int matched = countMatching(leaves, team, alert.getSamplePayload());
            long routedHere = sourceCounts.getOrDefault(team.getId(), 0L);
            double conditionShare = (double) matched / leaves.size();
            double score = sourceTotal == 0
                    ? conditionShare
                    : CONDITION_WEIGHT * conditionShare + HISTORY_WEIGHT * routedHere / sourceTotal;
            if (score > 0) {
                suggestions.add(new TeamSuggestion(team.getId(), team.getName(), round(score),
                        reason(matched, leaves.size(), routedHere, source)));
            }
        }
        return suggestions.stream()
                .sorted(Comparator.comparingDouble(TeamSuggestion::score).reversed().thenComparingLong(TeamSuggestion::teamId))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private int countMatching(List<JsonNode> leaves, Team team, JsonNode event) {
        int matched = 0;
        for (JsonNode leaf : leaves) {
            try {
                if (compiler.compile(leaf, team.isStrict()).test(event)) {
                    matched++;
                }
            } catch (RuntimeException e) {
                log.debug("Leaf {} of team {} could not be evaluated", leaf, team.getId(), e);
            }
        }
        return matched;
    }

    private static String reason(int matched, int total, long routedHere, String source) {
        String text = matched + " of " + total + (total == 1 ? " condition matches" : " conditions match");
        if (routedHere > 0) {
            text += "; " + routedHere + (routedHere == 1 ? " event" : " events") + " from source " + source
                    + (routedHere == 1 ? " was" : " were") + " routed to this team";
        }
        return text;
    }

    private static double round(double score) {
        return Math.round(score * 100) / 100.0;
    }
}
