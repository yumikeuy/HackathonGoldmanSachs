package com.hackathon.alerting.rules.api;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.model.Severity;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.runbooks.matching.RunbookSelectionChanged;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * Rule CRUD. Conditions are validated by compiling them with the team's strict flag (invalid → 400), and every
 * change is announced with {@link ConfigKinds#RULES} so all instances reload their routing cache after commit —
 * a saved rule takes effect without restart. Changing a rule's "always attach" runbook also re-selects the runbook of
 * open alerts ({@link RunbookSelectionChanged}, handled by {@code RunbookResync} in the same transaction).
 */
@Service
public class RuleService {

    private final RuleRepository rules;
    private final TeamRepository teams;
    private final RunbookRepository runbooks;
    private final AlertRepository alerts;
    private final ConditionCompiler compiler;
    private final ConfigChangedPublisher configChanged;
    private final ApplicationEventPublisher events;

    public RuleService(RuleRepository rules, TeamRepository teams, RunbookRepository runbooks, AlertRepository alerts,
                       ConditionCompiler compiler, ConfigChangedPublisher configChanged, ApplicationEventPublisher events) {
        this.rules = rules;
        this.teams = teams;
        this.runbooks = runbooks;
        this.alerts = alerts;
        this.compiler = compiler;
        this.configChanged = configChanged;
        this.events = events;
    }

    /** Rules of one team, or of all teams when {@code teamId} is {@code null}, in evaluation order. */
    @Transactional(readOnly = true)
    public List<RuleDto> list(Long teamId) {
        List<Rule> found = teamId == null
                ? rules.findAllByOrderByTeamIdAscPriorityAscIdAsc()
                : rules.findAllByTeamIdOrderByPriorityAscIdAsc(teamId);
        return found.stream().map(RuleDto::from).toList();
    }

    @Transactional(readOnly = true)
    public RuleDto get(long id) {
        return RuleDto.from(load(id));
    }

    @Transactional
    public RuleDto create(RuleInput input) {
        Team team = teams.findById(input.teamId())
                .orElseThrow(() -> new InvalidRequestException("Team " + input.teamId() + " does not exist"));
        Rule rule = new Rule(team, input.name());
        apply(rule, input, team);
        rules.save(rule);
        configChanged.publish(ConfigKinds.RULES);
        return RuleDto.from(rule);
    }

    /** Replaces every field. A rule cannot move to another team. */
    @Transactional
    public RuleDto update(long id, RuleInput input) {
        Rule rule = load(id);
        Team team = rule.getTeam();
        if (!team.getId().equals(input.teamId())) {
            throw new InvalidRequestException("A rule cannot move to another team; create a new rule in team " + input.teamId());
        }
        Long overrideBefore = overrideRunbookId(rule);
        rule.setName(input.name());
        apply(rule, input, team);
        rules.saveAndFlush(rule); // flush now so @UpdateTimestamp is reflected in the response
        if (!Objects.equals(overrideBefore, overrideRunbookId(rule))) {
            // the rule's "always attach" runbook decides the runbook of its open alerts — re-select them now
            events.publishEvent(RunbookSelectionChanged.ruleOverrideChanged(rule.getId()));
        }
        configChanged.publish(ConfigKinds.RULES);
        return RuleDto.from(rule);
    }

    /** @throws RuleInUseException if alerts reference the rule */
    @Transactional
    public void delete(long id) {
        Rule rule = load(id);
        if (alerts.existsByRuleId(id)) {
            throw new RuleInUseException(id);
        }
        rules.delete(rule);
        configChanged.publish(ConfigKinds.RULES);
    }

    /** Copies the input onto the rule, applying defaults and validating everything the entity cannot. */
    private void apply(Rule rule, RuleInput input, Team team) {
        rule.setCondition(validCondition(input.condition(), team, "condition"));
        rule.setResolveCondition(validCondition(input.resolveCondition(), team, "resolveCondition"));
        rule.setPriority(Objects.requireNonNullElse(input.priority(), Rule.DEFAULT_PRIORITY));
        rule.setActive(Objects.requireNonNullElse(input.active(), true));
        Severity severity = Objects.requireNonNullElse(input.assignedSeverity(), Severity.MEDIUM);
        if (!severity.isAssignableByRule()) {
            throw new InvalidRequestException("assignedSeverity " + severity + " is reserved for unrouted alerts");
        }
        rule.setAssignedSeverity(severity);
        rule.setChannels(Objects.requireNonNullElse(input.channels(), List.of(Channel.UI)));
        rule.setGroupByPaths(Objects.requireNonNullElse(input.groupByPaths(), List.of()));
        rule.setGroupMode(Objects.requireNonNullElse(input.groupMode(), GroupMode.WHILE_OPEN));
        rule.setDedupWindowSec(Objects.requireNonNullElse(input.dedupWindowSec(), Rule.DEFAULT_DEDUP_WINDOW_SEC));
        rule.setPresentation(input.presentation());
        rule.setOverrideRunbook(input.overrideRunbookId() == null ? null : runbooks.findById(input.overrideRunbookId())
                .orElseThrow(() -> new InvalidRequestException("Runbook " + input.overrideRunbookId() + " does not exist")));
    }

    /** {@code null} / JSON null mean "no condition"; anything else must compile (else 400 with the engine's message). */
    private JsonNode validCondition(JsonNode condition, Team team, String field) {
        if (condition == null || condition.isNull()) {
            return null;
        }
        try {
            compiler.compile(condition, team.isStrict());
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException(field + ": " + e.getBody().getDetail(), e);
        }
        return condition;
    }

    private static Long overrideRunbookId(Rule rule) {
        return rule.getOverrideRunbook() == null ? null : rule.getOverrideRunbook().getId();
    }

    private Rule load(long id) {
        return rules.findById(id).orElseThrow(() -> new NotFoundException("Rule", id));
    }
}
