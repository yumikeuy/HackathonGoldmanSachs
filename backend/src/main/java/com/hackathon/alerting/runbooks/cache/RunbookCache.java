package com.hackathon.alerting.runbooks.cache;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.condition.InvalidConditionException;
import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * In-memory cache of compiled runbook conditions (owner: Role 3).
 *
 * <p>Loads metadata via {@link RunbookRepository#findAllSummaries()} (never touching {@code bytea}
 * file contents) at startup before Kafka listeners start, and reloads atomically whenever
 * {@link ConfigKinds#RUNBOOKS} or {@link ConfigKinds#TEAMS} changes (since a team's {@code strict}
 * flag controls how its runbook conditions compile).
 *
 * <p>A stored runbook with an invalid condition is logged and excluded from automatic matching,
 * rather than aborting {@link #reload()}.
 */
@Component
public class RunbookCache implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RunbookCache.class);

    private final TeamRepository teams;
    private final RunbookRepository runbooks;
    private final ConditionCompiler compiler;

    private volatile RunbookCatalog catalog = RunbookCatalog.EMPTY;

    public RunbookCache(TeamRepository teams, RunbookRepository runbooks, ConditionCompiler compiler) {
        this.teams = teams;
        this.runbooks = runbooks;
        this.compiler = compiler;
    }

    /** Current immutable runbook snapshot. Never {@code null}. */
    public RunbookCatalog current() {
        return catalog;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reload();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.is(ConfigKinds.RUNBOOKS) || event.is(ConfigKinds.TEAMS)) {
            reload();
        }
    }

    /** Rebuilds the catalog from Postgres and swaps the volatile reference atomically. */
    public synchronized void reload() {
        Map<Long, Boolean> strictByTeamId = new HashMap<>();
        for (Team team : teams.findAllByOrderByIdAsc()) {
            if (team.getId() != null) {
                strictByTeamId.put(team.getId(), team.isStrict());
            }
        }

        List<RunbookSummary> summaries = runbooks.findAllSummaries();
        Map<Long, CompiledRunbook> byId = new LinkedHashMap<>(summaries.size());
        Map<Long, List<CompiledRunbook>> byTeamId = new LinkedHashMap<>();
        List<CompiledRunbook> globalRunbooks = new ArrayList<>();

        for (RunbookSummary summary : summaries) {
            if (summary.id() == null) {
                continue;
            }
            if (isAbsent(summary.condition())) {
                byId.put(summary.id(), new CompiledRunbook(summary.id(), summary.teamId(), summary.fileName(), null));
                continue;
            }

            boolean strict = !summary.isGlobal() && strictByTeamId.getOrDefault(summary.teamId(), false);
            Predicate<JsonNode> predicate;
            try {
                predicate = compiler.compile(summary.condition(), strict);
            } catch (InvalidConditionException e) {
                log.error("Runbook {} '{}' has an invalid condition and is skipped from auto-matching: {}",
                        summary.id(), summary.fileName(), e.getBody().getDetail());
                byId.put(summary.id(), new CompiledRunbook(summary.id(), summary.teamId(), summary.fileName(), null));
                continue;
            }

            CompiledRunbook compiled = new CompiledRunbook(summary.id(), summary.teamId(), summary.fileName(), predicate);
            byId.put(summary.id(), compiled);
            if (summary.isGlobal()) {
                globalRunbooks.add(compiled);
            } else {
                byTeamId.computeIfAbsent(summary.teamId(), id -> new ArrayList<>()).add(compiled);
            }
        }

        catalog = new RunbookCatalog(byId, byTeamId, globalRunbooks);
        int teamMatchableCount = byTeamId.values().stream().mapToInt(List::size).sum();
        log.info("Runbook cache loaded: {} total ({} team-matchable, {} global-matchable)",
                catalog.size(), teamMatchableCount, globalRunbooks.size());
    }

    private static boolean isAbsent(JsonNode condition) {
        return condition == null || condition.isNull();
    }
}
