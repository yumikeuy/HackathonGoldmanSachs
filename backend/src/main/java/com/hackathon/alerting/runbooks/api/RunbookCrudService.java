package com.hackathon.alerting.runbooks.api;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookFile;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runbook CRUD and file serving (owner: Role 3).
 *
 * <p>Listing and metadata endpoints use {@link RunbookSummary} projections so {@code runbooks.content}
 * is never loaded except during {@link #downloadFile}. Mutations validate any {@code condition} tree
 * with {@link ConditionCompiler} and announce {@link ConfigKinds#RUNBOOKS} after commit.
 */
@Service
public class RunbookCrudService {

    private static final int FILE_NAME_MAX_LENGTH = 255;

    private final RunbookRepository runbooks;
    private final TeamRepository teams;
    private final ConditionCompiler compiler;
    private final ConfigChangedPublisher configChanged;
    private final JsonMapper json;

    public RunbookCrudService(RunbookRepository runbooks, TeamRepository teams, ConditionCompiler compiler,
                              ConfigChangedPublisher configChanged, JsonMapper json) {
        this.runbooks = runbooks;
        this.teams = teams;
        this.compiler = compiler;
        this.configChanged = configChanged;
        this.json = json;
    }

    /**
     * With {@code teamId}, returns that team's runbooks plus global ones; otherwise returns all runbooks.
     */
    @Transactional(readOnly = true)
    public List<RunbookDto> list(Long teamId) {
        if (teamId == null) {
            return runbooks.findAllSummaries().stream()
                    .map(RunbookDto::from)
                    .toList();
        }
        return Stream.concat(
                        runbooks.findSummariesByTeamId(teamId).stream(),
                        runbooks.findGlobalSummaries().stream())
                .sorted(Comparator.comparingLong(RunbookSummary::id))
                .map(RunbookDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RunbookDto get(long id) {
        return runbooks.findSummaryById(id)
                .map(RunbookDto::from)
                .orElseThrow(() -> new NotFoundException("Runbook", id));
    }

    @Transactional
    public RunbookDto create(MultipartFile file, Long teamId, String conditionJson) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("Runbook file must not be empty");
        }
        Team team = resolveTeam(teamId);
        JsonNode condition = parseAndValidateCondition(conditionJson, team);
        String fileName = cleanFileName(file);
        byte[] content = readBytes(file);

        Runbook runbook = new Runbook(team, fileName, content);
        runbook.setCondition(condition);
        Runbook saved = runbooks.save(runbook);
        configChanged.publish(ConfigKinds.RUNBOOKS);
        return RunbookDto.from(saved);
    }

    @Transactional
    public RunbookDto update(long id, MultipartFile file, Long teamId, String conditionJson) {
        Runbook runbook = runbooks.findById(id)
                .orElseThrow(() -> new NotFoundException("Runbook", id));
        Team team = resolveTeam(teamId);
        JsonNode condition = parseAndValidateCondition(conditionJson, team);

        runbook.setTeam(team);
        runbook.setCondition(condition);
        if (file != null) {
            if (file.isEmpty()) {
                throw new InvalidRequestException("Replacement runbook file must not be empty");
            }
            runbook.replaceFile(cleanFileName(file), readBytes(file));
        }

        Runbook saved = runbooks.save(runbook);
        configChanged.publish(ConfigKinds.RUNBOOKS);
        return RunbookDto.from(saved);
    }

    @Transactional
    public void delete(long id) {
        if (!runbooks.existsById(id)) {
            throw new NotFoundException("Runbook", id);
        }
        runbooks.deleteById(id);
        configChanged.publish(ConfigKinds.RUNBOOKS);
    }

    @Transactional(readOnly = true)
    public RunbookFile downloadFile(long id) {
        return runbooks.findFileById(id)
                .orElseThrow(() -> new NotFoundException("Runbook", id));
    }

    private Team resolveTeam(Long teamId) {
        if (teamId == null) {
            return null;
        }
        return teams.findById(teamId)
                .orElseThrow(() -> new InvalidRequestException("Team " + teamId + " does not exist"));
    }

    private JsonNode parseAndValidateCondition(String conditionJson, Team team) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(conditionJson);
        } catch (JacksonException e) {
            throw new InvalidRequestException("condition: invalid JSON", e);
        }
        if (parsed == null || parsed.isNull()) {
            return null;
        }
        boolean strict = team != null && team.isStrict();
        try {
            compiler.compile(parsed, strict);
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException("condition: " + e.getBody().getDetail(), e);
        }
        return parsed;
    }

    private static String cleanFileName(MultipartFile file) {
        String name = StringUtils.getFilename(file.getOriginalFilename());
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("Runbook file name must not be blank");
        }
        if (name.length() > FILE_NAME_MAX_LENGTH) {
            throw new InvalidRequestException("Runbook file name must be at most " + FILE_NAME_MAX_LENGTH + " characters");
        }
        return name;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("Failed to read uploaded file", e);
        }
    }
}
