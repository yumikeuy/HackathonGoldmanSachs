package com.hackathon.alerting.dev;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Built-in sample events for runs without a template: every {@code classpath:dev/samples/*.json}, in file-name order.
 * The files are copies of the shared fixtures in {@code backend/src/test/resources/events} (test resources are not on
 * the runtime classpath).
 */
@Component
public class SampleEvents {

    static final String LOCATION = "classpath:dev/samples/*.json";

    private final List<JsonNode> samples;

    public SampleEvents(JsonMapper json) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        this.samples = Arrays.stream(resources)
                .sorted(Comparator.comparing(resource -> Objects.requireNonNullElse(resource.getFilename(), "")))
                .map(resource -> read(json, resource))
                .toList();
        if (samples.isEmpty()) {
            throw new IllegalStateException("No sample events found at " + LOCATION);
        }
    }

    /** The {@code index}-th sample, cycling. */
    public JsonNode get(long index) {
        return samples.get((int) (index % samples.size()));
    }

    public int size() {
        return samples.size();
    }

    private static JsonNode read(JsonMapper json, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sample event " + resource, e);
        }
    }
}
