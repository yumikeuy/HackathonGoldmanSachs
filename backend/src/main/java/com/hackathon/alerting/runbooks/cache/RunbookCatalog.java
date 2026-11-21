package com.hackathon.alerting.runbooks.cache;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of compiled runbooks held by {@link RunbookCache}.
 *
 * <p>Per-team and global lists preserve ascending {@code id} order so tie-breaking on multiple
 * matching runbooks deterministically selects the lowest id.
 *
 * @param byId           all known runbooks (including override-only entries without a condition)
 * @param byTeamId       auto-matchable team runbooks partitioned by {@code team_id}, ordered by {@code id}
 * @param globalRunbooks auto-matchable global runbooks ({@code team_id IS NULL}), ordered by {@code id}
 */
public record RunbookCatalog(
        Map<Long, CompiledRunbook> byId,
        Map<Long, List<CompiledRunbook>> byTeamId,
        List<CompiledRunbook> globalRunbooks
) {

    public static final RunbookCatalog EMPTY = new RunbookCatalog(Map.of(), Map.of(), List.of());

    public RunbookCatalog {
        byId = Map.copyOf(byId);
        Map<Long, List<CompiledRunbook>> immutableByTeam = new LinkedHashMap<>(byTeamId.size());
        for (Map.Entry<Long, List<CompiledRunbook>> entry : byTeamId.entrySet()) {
            immutableByTeam.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        byTeamId = Map.copyOf(immutableByTeam);
        globalRunbooks = List.copyOf(globalRunbooks);
    }

    /** Returns any cached runbook metadata (including override-only entries) by primary key. */
    public Optional<CompiledRunbook> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Returns auto-matchable runbooks owned by {@code teamId}, ordered by ascending id. */
    public List<CompiledRunbook> forTeam(long teamId) {
        return byTeamId.getOrDefault(teamId, List.of());
    }

    /** Returns the lowest-id auto-matchable runbook owned by {@code teamId} that matches {@code event}. */
    public Optional<CompiledRunbook> firstMatchingTeam(long teamId, JsonNode event) {
        for (CompiledRunbook runbook : forTeam(teamId)) {
            if (runbook.matches(event)) {
                return Optional.of(runbook);
            }
        }
        return Optional.empty();
    }

    /** Returns the lowest-id auto-matchable global runbook that matches {@code event}. */
    public Optional<CompiledRunbook> firstMatchingGlobal(JsonNode event) {
        for (CompiledRunbook runbook : globalRunbooks) {
            if (runbook.matches(event)) {
                return Optional.of(runbook);
            }
        }
        return Optional.empty();
    }

    /** Total number of runbooks in the catalog (team + global + override-only). */
    public int size() {
        return byId.size();
    }
}
