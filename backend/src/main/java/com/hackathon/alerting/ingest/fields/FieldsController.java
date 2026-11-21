package com.hackathon.alerting.ingest.fields;

import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.RuleRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code GET /api/fields}: field paths seen in recent events for condition autocompletion.
 *
 * <p>A field is not suggested if it looks like an ID or a timestamp (by name), if it has too many distinct
 * values to be a useful filter, or if a strict team (or one of its rules) uses it — strict conditions do not
 * take part in suggestions (AGENTS.md §5).
 */
@RestController
public class FieldsController {

    /** More distinct values than this in 24 h — an identifier, not a category. */
    static final long MAX_SUGGESTED_CARDINALITY = 1000;

    private static final Pattern ID_OR_TIME = Pattern.compile(
            "^(id|uuid|guid|ts|timestamp|time|date|datetime)$|[_-](id|uuid|at|ts|time|date)$|timestamp$");

    private final FieldCatalog catalog;
    private final TeamRepository teams;
    private final RuleRepository rules;

    public FieldsController(FieldCatalog catalog, TeamRepository teams, RuleRepository rules) {
        this.catalog = catalog;
        this.teams = teams;
        this.rules = rules;
    }

    @GetMapping("/api/fields")
    @Transactional(readOnly = true)
    public List<FieldInfo> fields() {
        Set<String> strictPaths = strictPaths();
        return catalog.fields().stream()
                .map(field -> new FieldInfo(field.path(), field.type(), field.cardinality(),
                        isSuggested(field, strictPaths)))
                .toList();
    }

    static boolean isSuggested(FieldCatalog.KnownField field, Set<String> strictPaths) {
        String lastSegment = field.path().substring(field.path().lastIndexOf('.') + 1);
        return !strictPaths.contains(field.path())
                && !looksLikeIdOrTime(lastSegment)
                && field.cardinality() <= MAX_SUGGESTED_CARDINALITY;
    }

    /** {@code id}, {@code tradeId}, {@code trade_id}, {@code ts}, {@code created_at}, {@code timestamp}, … */
    static boolean looksLikeIdOrTime(String name) {
        if (name.endsWith("Id") || name.endsWith("ID") || name.endsWith("At")) {
            return true;
        }
        return ID_OR_TIME.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    private Set<String> strictPaths() {
        Set<String> paths = new HashSet<>();
        Set<Long> strictTeamIds = new HashSet<>();
        for (Team team : teams.findAllByOrderByIdAsc()) {
            if (team.isStrict()) {
                strictTeamIds.add(team.getId());
                collectPaths(team.getCondition(), paths);
            }
        }
        if (!strictTeamIds.isEmpty()) {
            for (Rule rule : rules.findAll()) {
                if (strictTeamIds.contains(rule.getTeam().getId())) {
                    collectPaths(rule.getCondition(), paths);
                    collectPaths(rule.getResolveCondition(), paths);
                }
            }
        }
        return paths;
    }

    /** Every {@code "path"} of the leaves of a condition tree (AGENTS.md §5). */
    static void collectPaths(JsonNode condition, Set<String> paths) {
        if (condition == null || condition.isNull()) {
            return;
        }
        if (condition.isArray()) {
            condition.forEach(child -> collectPaths(child, paths));
        } else if (condition.isObject()) {
            JsonNode path = condition.get("path");
            if (path != null && path.isString()) {
                paths.add(path.asString());
            }
            collectPaths(condition.get("and"), paths);
            collectPaths(condition.get("or"), paths);
        }
    }

    /** {@code FieldInfo} of openapi.yaml. */
    public record FieldInfo(String path, String type, long cardinality, boolean suggested) {
    }
}
