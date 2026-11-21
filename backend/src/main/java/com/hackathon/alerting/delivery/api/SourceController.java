package com.hackathon.alerting.delivery.api;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.web.ConflictException;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.repository.SourceRepository;
import com.hackathon.alerting.ingest.source.SourceKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * CRUD {@code /api/sources}. The name is the URL segment of {@code POST /api/ingest/{source}}; a {@code kafka}
 * source needs {@code config.topic}, a {@code file} source {@code config.path}. Every change is published as
 * {@link ConfigKinds#SOURCES}, which reloads the source cache, the external Kafka listeners and the file tailers
 * on all instances.
 *
 * <p>An HTTP source can require an API key ({@code POST/DELETE /api/sources/{id}/api-key}); only its SHA-256 is stored
 * in {@code config}, and it is never returned (docs/security.md).
 */
@RestController
@RequestMapping("/api/sources")
@Transactional
public class SourceController {

    private final SourceRepository sources;
    private final ConfigChangedPublisher configChanged;

    public SourceController(SourceRepository sources, ConfigChangedPublisher configChanged) {
        this.sources = sources;
        this.configChanged = configChanged;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<SourceDto> list() {
        return sources.findAllByOrderByIdAsc().stream().map(SourceDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public SourceDto get(@PathVariable long id) {
        return SourceDto.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDto create(@Valid @RequestBody SourceInput input) {
        validate(input);
        if (sources.existsByName(input.name())) {
            throw new ConflictException("Source '" + input.name() + "' already exists");
        }
        Source source = new Source(input.name(), input.type(), SourceKeys.withoutSecrets(input.config()));
        source.setActive(input.active() == null || input.active());
        return save(source);
    }

    @PutMapping("/{id}")
    public SourceDto update(@PathVariable long id, @Valid @RequestBody SourceInput input) {
        validate(input);
        Source source = find(id);
        if (!source.getName().equals(input.name()) && sources.existsByName(input.name())) {
            throw new ConflictException("Source '" + input.name() + "' already exists");
        }
        source.setName(input.name());
        source.setType(input.type());
        source.setConfig(keepApiKey(source.getConfig(), input.config()));
        source.setActive(input.active() == null || input.active());
        return save(source);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        sources.delete(find(id));
        configChanged.publish(ConfigKinds.SOURCES);
    }

    /**
     * Generates a new API key for an HTTP source; from now on {@code POST /api/ingest/{name}} requires it in
     * {@code X-Api-Key}. The key is returned only here — the database keeps its SHA-256. Calling again rotates the key.
     */
    @PostMapping("/{id}/api-key")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceApiKey createApiKey(@PathVariable long id) {
        Source source = find(id);
        if (source.getType() != SourceType.HTTP) {
            throw new InvalidRequestException("API keys apply to http sources only");
        }
        String key = SourceKeys.generate();
        ObjectNode config = source.getConfig().isObject() ? ((ObjectNode) source.getConfig()).deepCopy() : JsonNodeFactory.instance.objectNode();
        config.put(SourceKeys.HASH_FIELD, SourceKeys.hash(key));
        source.setConfig(config);
        save(source);
        return new SourceApiKey(source.getName(), key, SourceKeys.HEADER);
    }

    /** Removes the API key: the source accepts events without a key again. */
    @DeleteMapping("/{id}/api-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKey(@PathVariable long id) {
        Source source = find(id);
        source.setConfig(SourceKeys.withoutSecrets(source.getConfig()));
        save(source);
    }

    /** PUT replaces the config but must not silently drop (or let the client set) the key hash. */
    private static JsonNode keepApiKey(JsonNode current, JsonNode input) {
        JsonNode next = SourceKeys.withoutSecrets(input);
        if (!SourceKeys.isRequired(current)) {
            return next;
        }
        ObjectNode merged = next != null && next.isObject() ? ((ObjectNode) next).deepCopy() : JsonNodeFactory.instance.objectNode();
        merged.set(SourceKeys.HASH_FIELD, current.get(SourceKeys.HASH_FIELD));
        return merged;
    }

    private SourceDto save(Source source) {
        try {
            Source saved = sources.saveAndFlush(source);
            configChanged.publish(ConfigKinds.SOURCES);
            return SourceDto.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Source '" + source.getName() + "' already exists", e);
        }
    }

    private Source find(long id) {
        return sources.findById(id).orElseThrow(() -> new NotFoundException("Source", id));
    }

    private static void validate(SourceInput input) {
        if (input.config() != null && !input.config().isNull() && !input.config().isObject()) {
            throw new InvalidRequestException("config must be a JSON object");
        }
        if (input.type() == SourceType.KAFKA) {
            JsonNode topic = input.config() == null ? null : input.config().get("topic");
            if (topic == null || !topic.isString() || topic.asString().isBlank()) {
                throw new InvalidRequestException("A kafka source needs config.topic");
            }
        }
        if (input.type() == SourceType.FILE) {
            JsonNode path = input.config() == null ? null : input.config().get("path");
            if (path == null || !path.isString() || path.asString().isBlank()) {
                throw new InvalidRequestException("A file source needs config.path");
            }
        }
    }

    /** {@code SourceInput} of openapi.yaml. */
    public record SourceInput(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may contain only letters, digits, '.', '_' and '-'")
            String name,
            @NotNull SourceType type,
            JsonNode config,
            Boolean active) {
    }

    /** {@code Source} of openapi.yaml; the API key hash is never returned, only whether a key is required. */
    public record SourceDto(long id, String name, SourceType type, JsonNode config, boolean active, boolean apiKeyRequired,
                            Instant createdAt) {

        static SourceDto from(Source source) {
            return new SourceDto(source.getId(), source.getName(), source.getType(),
                    SourceKeys.withoutSecrets(source.getConfig()), source.isActive(),
                    SourceKeys.isRequired(source.getConfig()), source.getCreatedAt());
        }
    }

    /** {@code SourceApiKey} of openapi.yaml — shown once. */
    public record SourceApiKey(String sourceName, String apiKey, String header) {
    }
}
