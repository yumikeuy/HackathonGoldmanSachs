package com.hackathon.alerting.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST API matches its contract {@code frontend/openapi.yaml} by <b>paths + methods</b>:
 * every operation without {@code x-planned: true} is implemented, every planned one is not yet, and nothing is
 * implemented that the contract does not describe. Implemented operations are read from springdoc
 * ({@code /v3/api-docs}); path parameter names are ignored ({@code /api/rules/{id}} = {@code /api/rules/{ruleId}}).
 *
 * <p>When this fails: ship or un-plan the operation in {@code openapi.yaml} (the owning role, see {@code roles.md}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class ApiContractTest {

    static final Path CONTRACT = Path.of("..", "frontend", "openapi.yaml");
    static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

    @Value("${local.server.port}")
    private int port;

    @Test
    void implementedOperationsMatchTheContract() throws IOException {
        Set<String> planned = new TreeSet<>();
        Set<String> contracted = new TreeSet<>();
        readContract(contracted, planned);
        Set<String> implemented = implemented();
        assertThat(implemented).as("operations read from /v3/api-docs").hasSizeGreaterThan(20);

        Set<String> missing = new TreeSet<>(contracted);
        missing.removeAll(implemented);
        Set<String> plannedButImplemented = new TreeSet<>(planned);
        plannedButImplemented.retainAll(implemented);
        Set<String> notInContract = new TreeSet<>(implemented);
        notInContract.removeAll(contracted);
        notInContract.removeAll(planned);

        assertThat(missing).as("in openapi.yaml without x-planned, but not implemented").isEmpty();
        assertThat(plannedButImplemented).as("implemented, but still x-planned in openapi.yaml — remove the marker").isEmpty();
        assertThat(notInContract).as("implemented, but not described in openapi.yaml").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void readContract(Set<String> contracted, Set<String> planned) throws IOException {
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(CONTRACT)) {
            document = new Yaml().load(reader);
        }
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        paths.forEach((path, item) -> item.forEach((method, operation) -> {
            if (METHODS.contains(method)) {
                boolean isPlanned = operation instanceof Map<?, ?> op && Boolean.TRUE.equals(op.get("x-planned"));
                (isPlanned ? planned : contracted).add(key(method, path));
            }
        }));
    }

    private Set<String> implemented() {
        JsonNode docs = RestClient.create("http://localhost:" + port).get().uri("/v3/api-docs").retrieve().body(JsonNode.class);
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : docs.get("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                if (METHODS.contains(method.getKey())) {
                    operations.add(key(method.getKey(), path.getKey()));
                }
            }
        }
        return operations;
    }

    /** {@code "GET /api/rules/{}"} — method plus path with parameter names erased. */
    static String key(String method, String path) {
        return method.toUpperCase() + " " + path.replaceAll("\\{[^}]*}", "{}");
    }
}
