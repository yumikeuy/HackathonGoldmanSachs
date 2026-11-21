package com.hackathon.alerting.rules.cache;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.condition.InvalidConditionException;
import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory routing configuration: teams and their active rules with conditions compiled once
 * (AGENTS.md §5 "compiled once"; §10 "no configuration reads from the database per event").
 *
 * <p>Loaded when the context starts — before Kafka listener containers start consuming — and reloaded on
 * {@link ConfigChangedEvent} for {@link ConfigKinds#TEAMS} or {@link ConfigKinds#RULES}. Readers get an
 * immutable {@link RoutingTable}; a reload builds a new table and swaps the reference.
 *
 * <p>A team or rule whose stored condition does not compile is skipped (logged), not fatal: one broken
 * rule must not stop routing for everybody. Conditions are validated on save, so this only happens when the
 * condition engine became stricter than when the condition was stored.
 */
@Component
public class RoutingCache implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RoutingCache.class);

    private final TeamRepository teams;
    private final RuleRepository rules;
    private final ConditionCompiler compiler;

    private volatile RoutingTable table = RoutingTable.EMPTY;

    public RoutingCache(TeamRepository teams, RuleRepository rules, ConditionCompiler compiler) {
        this.teams = teams;
        this.rules = rules;
        this.compiler = compiler;
    }

    /** The current configuration. Never {@code null}. */
    public RoutingTable current() {
        return table;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reload();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.is(ConfigKinds.TEAMS) || event.is(ConfigKinds.RULES)) {
            reload();
        }
    }

    /** Rebuilds the table from the database. Synchronized so concurrent reloads cannot publish out of order. */
    public synchronized void reload() {
        Map<Long, List<Rule>> rulesByTeam = rules.findAllWithTeamByActiveTrueOrderByPriorityAscIdAsc().stream()
                .collect(Collectors.groupingBy(rule -> rule.getTeam().getId(), Collectors.toList()));

        List<TeamRoute> routes = new ArrayList<>();
        for (Team team : teams.findAllByOrderByIdAsc()) {
            Predicate<JsonNode> matcher = compileTeam(team);
            List<RuleRoute> ruleRoutes = rulesByTeam.getOrDefault(team.getId(), List.of()).stream()
                    .map(rule -> compileRule(rule, team.isStrict()))
                    .filter(Objects::nonNull)
                    .toList();
            routes.add(new TeamRoute(team.getId(), team.getName(), team.isStrict(), matcher, ruleRoutes));
        }
        table = new RoutingTable(routes);
        log.info("Routing table loaded: {} teams, {} active rules", routes.size(), table.ruleCount());
    }

    private Predicate<JsonNode> compileTeam(Team team) {
        if (isAbsent(team.getCondition())) {
            return event -> false;
        }
        try {
            return compiler.compile(team.getCondition(), team.isStrict());
        } catch (InvalidConditionException e) {
            log.error("Team {} '{}' has an invalid condition and matches no events: {}", team.getId(), team.getName(), e.getBody().getDetail());
            return event -> false;
        }
    }

    private RuleRoute compileRule(Rule rule, boolean strict) {
        try {
            Predicate<JsonNode> condition = compiler.compile(rule.getCondition(), strict);
            Predicate<JsonNode> resolve = isAbsent(rule.getResolveCondition()) ? null : compiler.compile(rule.getResolveCondition(), strict);
            return new RuleRoute(rule, strict, condition, resolve);
        } catch (InvalidConditionException e) {
            log.error("Rule {} '{}' has an invalid condition and is skipped: {}", rule.getId(), rule.getName(), e.getBody().getDetail());
            return null;
        }
    }

    private static boolean isAbsent(JsonNode condition) {
        return condition == null || condition.isNull();
    }
}
