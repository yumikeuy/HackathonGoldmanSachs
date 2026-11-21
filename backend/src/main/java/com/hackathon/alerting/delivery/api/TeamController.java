package com.hackathon.alerting.delivery.api;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.web.ConflictException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * CRUD {@code /api/teams}. The condition is validated by compiling it ({@link ConditionCompiler}, 400 with the
 * reason). Every change is published as {@link ConfigKinds#TEAMS}. A team that users, rules or alerts still
 * reference cannot be deleted (409).
 */
@RestController
@RequestMapping("/api/teams")
@Transactional
public class TeamController {

    private final TeamRepository teams;
    private final ConditionCompiler conditionCompiler;
    private final ConfigChangedPublisher configChanged;

    public TeamController(TeamRepository teams, ConditionCompiler conditionCompiler, ConfigChangedPublisher configChanged) {
        this.teams = teams;
        this.conditionCompiler = conditionCompiler;
        this.configChanged = configChanged;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<TeamDto> list() {
        return teams.findAllByOrderByIdAsc().stream().map(TeamDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TeamDto get(@PathVariable long id) {
        return TeamDto.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDto create(@Valid @RequestBody TeamInput input) {
        if (teams.existsByName(input.name())) {
            throw new ConflictException("Team '" + input.name() + "' already exists");
        }
        Team team = new Team(input.name());
        apply(input, team);
        return save(team);
    }

    @PutMapping("/{id}")
    public TeamDto update(@PathVariable long id, @Valid @RequestBody TeamInput input) {
        Team team = find(id);
        if (!team.getName().equals(input.name()) && teams.existsByName(input.name())) {
            throw new ConflictException("Team '" + input.name() + "' already exists");
        }
        team.setName(input.name());
        apply(input, team);
        return save(team);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        Team team = find(id);
        try {
            teams.delete(team);
            teams.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Team " + id + " still has users, rules or alerts", e);
        }
        configChanged.publish(ConfigKinds.TEAMS);
    }

    private void apply(TeamInput input, Team team) {
        boolean strict = input.strict() != null && input.strict();
        JsonNode condition = input.condition() == null || input.condition().isNull() ? null : input.condition();
        conditionCompiler.compile(condition, strict);
        team.setCondition(condition);
        team.setStrict(strict);
    }

    private TeamDto save(Team team) {
        try {
            Team saved = teams.saveAndFlush(team);
            configChanged.publish(ConfigKinds.TEAMS);
            return TeamDto.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Team '" + team.getName() + "' already exists", e);
        }
    }

    private Team find(long id) {
        return teams.findById(id).orElseThrow(() -> new NotFoundException("Team", id));
    }

    /** {@code TeamInput} of openapi.yaml. */
    public record TeamInput(@NotBlank @Size(max = 100) String name, JsonNode condition, Boolean strict) {
    }

    /** {@code Team} of openapi.yaml. */
    public record TeamDto(long id, String name, JsonNode condition, boolean strict, Instant createdAt) {

        static TeamDto from(Team team) {
            return new TeamDto(team.getId(), team.getName(), team.getCondition(), team.isStrict(), team.getCreatedAt());
        }
    }
}
