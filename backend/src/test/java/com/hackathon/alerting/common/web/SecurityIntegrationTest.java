package com.hackathon.alerting.common.web;

import com.hackathon.alerting.ingest.source.IngestRateLimiter;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The protections of docs/security.md, end to end over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class SecurityIntegrationTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private StringRedisTemplate redis;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void apiKeyProtectedSourceRejectsEventsWithoutTheKey() {
        String name = "secured-" + UUID.randomUUID();
        long id = http().post().uri("/api/sources").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "type", "http")).retrieve().body(JsonNode.class).get("id").asLong();
        assertThat(ingest(name, null, "{\"a\":1}")).isEqualTo(202);

        ResponseEntity<JsonNode> created = http().post().uri("/api/sources/{id}/api-key", id).retrieve().toEntity(JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String key = created.getBody().get("apiKey").asString();

        assertThat(ingest(name, null, "{\"a\":1}")).isEqualTo(401);
        assertThat(ingest(name, "src_wrong", "{\"a\":1}")).isEqualTo(401);
        assertThat(ingest(name, key, "{\"a\":1}")).isEqualTo(202);

        JsonNode source = http().get().uri("/api/sources/{id}", id).retrieve().body(JsonNode.class);
        assertThat(source.get("apiKeyRequired").asBoolean()).isTrue();
        assertThat(source.toString()).doesNotContain("apiKeySha256").doesNotContain(key);

        // PUT without the hash keeps the key; a client cannot plant its own hash
        http().put().uri("/api/sources/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "type", "http", "config", Map.of("apiKeySha256", "00"))).retrieve().toBodilessEntity();
        assertThat(ingest(name, key, "{\"a\":1}")).isEqualTo(202);

        assertThat(http().delete().uri("/api/sources/{id}/api-key", id).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(204);
        assertThat(ingest(name, null, "{\"a\":1}")).isEqualTo(202);
    }

    @Test
    void oversizedAndDeeplyNestedEventsAreRefused() {
        String name = "limits-" + UUID.randomUUID();
        http().post().uri("/api/sources").contentType(MediaType.APPLICATION_JSON).body(Map.of("name", name, "type", "http"))
                .retrieve().toBodilessEntity();

        assertThat(ingest(name, null, "{\"x\":\"" + "a".repeat(1024 * 1024) + "\"}")).isEqualTo(413);
        assertThat(ingest(name, null, "[".repeat(200) + "]".repeat(200))).isEqualTo(400);
        assertThat(ingest(name, null, "{\"ok\":true}")).isEqualTo(202);
    }

    @Test
    void sourceRateLimitIsPerSecondAndPerSource() {
        IngestRateLimiter limiter = new IngestRateLimiter(redis, 3);
        String source = "rl-" + UUID.randomUUID();
        assertThat(limiter.tryAcquire(source)).isTrue();
        assertThat(limiter.tryAcquire(source)).isTrue();
        assertThat(limiter.tryAcquire(source)).isTrue();
        assertThat(limiter.tryAcquire(source)).isFalse();
        assertThat(limiter.tryAcquire("other-" + source)).isTrue();
    }

    @Test
    void uploadedHtmlRunbookIsDownloadedNeverRendered() {
        long evil = upload("evil.html", "<html><script>fetch('/api/teams')</script></html>");
        ResponseEntity<byte[]> html = http().get().uri("/api/runbooks/{id}/file", evil).retrieve().toEntity(byte[].class);
        assertThat(html.getStatusCode().value()).isEqualTo(200);
        assertThat(html.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(html.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(html.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        long disguised = upload("notes.txt", "<script>alert(1)</script>");
        assertThat(http().get().uri("/api/runbooks/{id}/file", disguised).retrieve().toEntity(byte[].class)
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");

        long plain = upload("restart.txt", "1. Restart the workflow");
        assertThat(http().get().uri("/api/runbooks/{id}/file", plain).retrieve().toEntity(byte[].class)
                .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
    }

    @Test
    void responsesCarryDefensiveHeadersAndCorsIsRestricted() {
        HttpHeaders headers = http().get().uri("/api/teams").retrieve().toBodilessEntity().getHeaders();
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'none'");

        assertThat(preflight("http://evil.example")).isEqualTo(403);
        assertThat(preflight("http://localhost:5173")).isEqualTo(200);
    }

    private int ingest(String source, String apiKey, String body) {
        return http().post().uri("/api/ingest/{s}", source).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> { if (apiKey != null) h.set("X-Api-Key", apiKey); })
                .body(body).retrieve().toBodilessEntity().getStatusCode().value();
    }

    private long upload(String fileName, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        JsonNode runbook = http().post().uri("/api/runbooks").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(JsonNode.class);
        return runbook.get("id").asLong();
    }

    private int preflight(String origin) {
        return http().method(HttpMethod.OPTIONS).uri("/api/teams")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .retrieve().toBodilessEntity().getStatusCode().value();
    }
}
