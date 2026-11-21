package com.hackathon.alerting.common.security;

import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.lang.reflect.Type;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** UI login with {@code alerting.security.jwt.enabled=true}: tokens, roles, rotation, signed links, WebSocket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "alerting.security.jwt.enabled=true",
        "alerting.security.admin.password=test-admin-password"})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class AuthIntegrationTest {

    static final String ADMIN_EMAIL = "admin@alerting.local";
    static final String ADMIN_PASSWORD = "test-admin-password";

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private SimpMessagingTemplate websocket;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void apiRequiresATokenAndRolesDecideWhatIsAllowed() {
        assertThat(status("GET", "/api/teams", null, null)).isEqualTo(401);
        assertThat(http().get().uri("/api/teams").retrieve().toEntity(JsonNode.class).getHeaders()
                .getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();

        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        assertThat(status("GET", "/api/teams", admin, null)).isEqualTo(200);
        long teamId = http().post().uri("/api/teams").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Team " + UUID.randomUUID()))
                .retrieve().body(JsonNode.class).get("id").asLong();

        String viewer = createUserAndLogin(admin, teamId, "VIEWER");
        String operator = createUserAndLogin(admin, teamId, "OPERATOR");

        assertThat(status("GET", "/api/alerts?teamId=" + teamId, viewer, null)).isEqualTo(200);
        assertThat(status("POST", "/api/teams", viewer, Map.of("name", "x"))).isEqualTo(403);
        assertThat(status("POST", "/api/alerts/999999/ack", viewer, Map.of())).isEqualTo(403);
        // OPERATOR passes the role check; the alert just does not exist
        assertThat(status("POST", "/api/alerts/999999/ack", operator, Map.of())).isEqualTo(404);
        assertThat(status("POST", "/api/teams", operator, Map.of("name", "x"))).isEqualTo(403);

        JsonNode me = http().get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + operator)
                .retrieve().body(JsonNode.class);
        assertThat(me.get("role").asString()).isEqualTo("OPERATOR");
        assertThat(me.get("teamId").asLong()).isEqualTo(teamId);

        assertThat(status("GET", "/api/teams", "not.a.jwt", null)).isEqualTo(401);
    }

    @Test
    void refreshTokensRotateAndLogoutRevokes() {
        JsonNode first = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String refresh1 = first.get("refreshToken").asString();

        JsonNode second = refresh(refresh1);
        assertThat(second.get("accessToken").asString()).isNotBlank();
        String refresh2 = second.get("refreshToken").asString();
        assertThat(refresh2).isNotEqualTo(refresh1);

        assertThat(refreshStatus(refresh1)).as("a used refresh token is dead").isEqualTo(401);
        assertThat(http().post().uri("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + second.get("accessToken").asString())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("refreshToken", refresh2)).retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(204);
        assertThat(refreshStatus(refresh2)).as("logged out").isEqualTo(401);
    }

    @Test
    void deactivatedUsersCannotRefreshAndPasswordsAreNeverReturned() {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        long teamId = http().post().uri("/api/teams").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Team " + UUID.randomUUID()))
                .retrieve().body(JsonNode.class).get("id").asLong();
        String email = UUID.randomUUID() + "@bank.example";
        JsonNode user = http().post().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("teamId", teamId, "name", "Eve", "email", email, "password", "correct-horse", "role", "OPERATOR"))
                .retrieve().body(JsonNode.class);
        assertThat(user.toString()).doesNotContain("correct-horse").doesNotContain("passwordHash");
        assertThat(user.get("canLogIn").asBoolean()).isTrue();

        String refresh = login(email, "correct-horse").get("refreshToken").asString();
        http().put().uri("/api/users/{id}", user.get("id").asLong()).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("teamId", teamId, "name", "Eve", "email", email, "active", false)).retrieve().toBodilessEntity();

        assertThat(refreshStatus(refresh)).isEqualTo(401);
        assertThat(loginStatus(email, "correct-horse")).isEqualTo(401);
    }

    @Test
    void wrongPasswordsAreRefusedAndBruteForceIsThrottled() {
        String email = "nobody-" + UUID.randomUUID() + "@bank.example";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            assertThat(loginStatus(email, "guess-" + i)).isEqualTo(401);
        }
        assertThat(loginStatus(email, "guess-x")).isEqualTo(429);
    }

    @Test
    void ingestHealthAndDocsStayOpen() {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        String source = "open-" + UUID.randomUUID();
        http().post().uri("/api/sources").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", source, "type", "http")).retrieve().toBodilessEntity();

        assertThat(status("POST", "/api/ingest/" + source, null, Map.of("a", 1))).isEqualTo(202);
        // open without a token; 200 or 503 depending on whether MailHog & co. run next to the test, never 401/403
        assertThat(status("GET", "/actuator/health", null, null)).isIn(200, 503);
        assertThat(status("GET", "/v3/api-docs", null, null)).isEqualTo(200);
    }

    @Test
    void runbookFilesNeedATokenOrAValidSignedLink() {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("1. Restart".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "restart.txt";
            }
        });
        long id = http().post().uri("/api/runbooks").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(JsonNode.class).get("id").asLong();

        assertThat(status("GET", "/api/runbooks/" + id + "/file", null, null)).isEqualTo(401);
        assertThat(status("GET", "/api/runbooks/" + id + "/file", admin, null)).isEqualTo(200);

        String url = http().get().uri("/api/auth/runbook-links/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .retrieve().body(JsonNode.class).get("url").asString();
        assertThat(status("GET", url, null, null)).isEqualTo(200);
        assertThat(status("GET", url.replace("sig=", "sig=x"), null, null)).isEqualTo(401);
        assertThat(status("GET", url.replace("/" + id + "/", "/" + (id + 1) + "/"), null, null)).as("other file").isEqualTo(401);
    }

    @Test
    void liveFeedRequiresATokenOnStompConnect() throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        String url = "ws://localhost:" + port + "/ws";

        StompHeaders anonymous = new StompHeaders();
        assertThatThrownBy(() -> stomp.connectAsync(url, new WebSocketHttpHeaders(), anonymous, new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

        StompHeaders authenticated = new StompHeaders();
        authenticated.add("Authorization", "Bearer " + login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString());
        StompSession session = stomp.connectAsync(url, new WebSocketHttpHeaders(), authenticated, new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void nonAdministratorsSeeOnlyTheirOwnTeam() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        String marker = UUID.randomUUID().toString();
        long teamA = createTeamWithRule(admin, "A-" + marker);
        long teamB = createTeamWithRule(admin, "B-" + marker);
        String source = "iso-" + marker;
        http().post().uri("/api/sources").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", source, "type", "http")).retrieve().toBodilessEntity();
        long alertA = awaitAlert(admin, teamA, source, "A-" + marker);
        long alertB = awaitAlert(admin, teamB, source, "B-" + marker);
        long ruleB = http().get().uri("/api/rules?teamId={t}", teamB).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .retrieve().body(JsonNode.class).get(0).get("id").asLong();

        String viewer = createUserAndLogin(admin, teamA, "VIEWER");
        String operator = createUserAndLogin(admin, teamA, "OPERATOR");

        JsonNode teams = http().get().uri("/api/teams").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer).retrieve().body(JsonNode.class);
        assertThat(teams).hasSize(1);
        assertThat(teams.get(0).get("id").asLong()).isEqualTo(teamA);
        JsonNode users = http().get().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer).retrieve().body(JsonNode.class);
        users.forEach(u -> assertThat(u.get("teamId").asLong()).isEqualTo(teamA));

        assertThat(status("GET", "/api/alerts?teamId=" + teamA, viewer, null)).isEqualTo(200);
        assertThat(status("GET", "/api/alerts?teamId=" + teamB, viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/alerts", viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/alerts?unrouted=true", viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/alerts/" + alertA, viewer, null)).isEqualTo(200);
        assertThat(status("GET", "/api/alerts/" + alertB, viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/alerts/" + alertB + "/audit", viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/rules?teamId=" + teamB, viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/rules/" + ruleB, viewer, null)).isEqualTo(403);
        assertThat(status("GET", "/api/teams/" + teamB, viewer, null)).isEqualTo(404);
        for (String adminOnly : new String[]{"/api/sources", "/api/simulator/deliveries", "/api/events/failed", "/api/fields",
                "/api/ingest/lag", "/api/alerts/" + alertA + "/team-suggestions"}) {
            assertThat(status("GET", adminOnly, viewer, null)).as(adminOnly).isEqualTo(403);
        }

        assertThat(status("POST", "/api/alerts/" + alertB + "/ack", operator, Map.of())).isEqualTo(403);
        assertThat(status("POST", "/api/alerts/" + alertA + "/ack", operator, Map.of())).isEqualTo(200);
        assertThat(status("GET", "/api/alerts?teamId=" + teamB, admin, null)).isEqualTo(200);
        assertThat(status("GET", "/api/alerts?unrouted=true", admin, null)).isEqualTo(200);

        // the live feed: own team only
        assertThat(receives(viewer, "/topic/alerts/" + teamA)).as("own team feed").isTrue();
        assertThat(receives(viewer, "/topic/alerts/" + teamB)).as("other team feed").isFalse();
        assertThat(receives(viewer, "/topic/unrouted")).as("unrouted feed").isFalse();
        assertThat(receives(admin, "/topic/unrouted")).as("admin: unrouted feed").isTrue();
    }

    @Test
    void operatorsManageRunbooksOfTheirOwnTeamOnly() {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asString();
        String marker = UUID.randomUUID().toString();
        long teamA = createTeamWithRule(admin, "RB-A-" + marker);
        long teamB = createTeamWithRule(admin, "RB-B-" + marker);
        String viewer = createUserAndLogin(admin, teamA, "VIEWER");
        String operator = createUserAndLogin(admin, teamA, "OPERATOR");

        // create: own team only; no global runbooks, no other teams; viewers cannot
        long own = createRunbook(operator, teamA);
        assertThat(uploadStatus("POST", "/api/runbooks", operator, teamB)).isEqualTo(403);
        assertThat(uploadStatus("POST", "/api/runbooks", operator, null)).as("global").isEqualTo(403);
        assertThat(uploadStatus("POST", "/api/runbooks", viewer, teamA)).isEqualTo(403);
        long global = createRunbook(admin, null);
        long otherTeam = createRunbook(admin, teamB);

        // edit: own team's runbooks, and they stay in that team
        assertThat(uploadStatus("PUT", "/api/runbooks/" + own, operator, teamA)).isEqualTo(200);
        assertThat(uploadStatus("PUT", "/api/runbooks/" + own, operator, teamB)).as("move to another team").isEqualTo(403);
        assertThat(uploadStatus("PUT", "/api/runbooks/" + own, operator, null)).as("make global").isEqualTo(403);
        assertThat(uploadStatus("PUT", "/api/runbooks/" + global, operator, teamA)).isEqualTo(403);
        assertThat(uploadStatus("PUT", "/api/runbooks/" + otherTeam, operator, teamA)).isEqualTo(403);
        assertThat(uploadStatus("PUT", "/api/runbooks/" + own, viewer, teamA)).isEqualTo(403);

        // read: own team's and global runbooks, as before
        assertThat(status("GET", "/api/runbooks/" + global, operator, null)).isEqualTo(200);
        assertThat(status("GET", "/api/runbooks/" + otherTeam, operator, null)).isEqualTo(403);

        // delete: own team's only
        assertThat(status("DELETE", "/api/runbooks/" + global, operator, null)).isEqualTo(403);
        assertThat(status("DELETE", "/api/runbooks/" + otherTeam, operator, null)).isEqualTo(403);
        assertThat(status("DELETE", "/api/runbooks/" + own, viewer, null)).isEqualTo(403);
        assertThat(status("DELETE", "/api/runbooks/" + own, operator, null)).isEqualTo(204);

        // administrators keep full control
        assertThat(uploadStatus("PUT", "/api/runbooks/" + otherTeam, admin, null)).as("admin makes it global").isEqualTo(200);
        assertThat(status("DELETE", "/api/runbooks/" + global, admin, null)).isEqualTo(204);
    }

    private long createTeamWithRule(String admin, String desk) {
        long teamId = http().post().uri("/api/teams").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Team " + desk, "condition", Map.of("path", "desk", "op", "==", "value", desk)))
                .retrieve().body(JsonNode.class).get("id").asLong();
        http().post().uri("/api/rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("teamId", teamId, "name", "All of " + desk, "channels", new String[]{"UI"})).retrieve().toBodilessEntity();
        return teamId;
    }

    private long awaitAlert(String admin, long teamId, String source, String desk) {
        AtomicLong id = new AtomicLong();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            http().post().uri("/api/ingest/{s}", source).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"desk\":\"" + desk + "\"}").retrieve().toBodilessEntity();
            JsonNode items = http().get().uri("/api/alerts?teamId={t}", teamId).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                    .retrieve().body(JsonNode.class).get("items");
            if (items.isEmpty()) {
                return false;
            }
            id.set(items.get(0).get("id").asLong());
            return true;
        });
        return id.get();
    }

    /** Subscribes, then the server broadcasts to the destination: true if the message reached this client. */
    private boolean receives(String token, String destination) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(payload);
            }
        });
        Thread.sleep(500);
        websocket.convertAndSend(destination, (Object) Map.of("probe", true));
        boolean got = received.poll(3, TimeUnit.SECONDS) != null;
        assertThat(session.isConnected()).as("the connection survives a refused subscription").isTrue();
        session.disconnect();
        return got;
    }

    // ------------------------------------------------------------------ helpers

    private String createUserAndLogin(String adminToken, long teamId, String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@bank.example";
        int created = http().post().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("teamId", teamId, "name", role, "email", email, "password", "password-" + role, "role", role))
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(created).isEqualTo(201);
        return login(email, "password-" + role).get("accessToken").asString();
    }

    private JsonNode login(String email, String password) {
        var response = http().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).as("login " + email).isEqualTo(200);
        return response.getBody();
    }

    private int loginStatus(String email, String password) {
        return http().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)).retrieve().toBodilessEntity().getStatusCode().value();
    }

    private JsonNode refresh(String refreshToken) {
        return http().post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken)).retrieve().body(JsonNode.class);
    }

    private int refreshStatus(String refreshToken) {
        return http().post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken)).retrieve().toBodilessEntity().getStatusCode().value();
    }

    /** Uploads a small runbook ({@code teamId} null = global) and returns its id; fails unless 201. */
    private long createRunbook(String token, Long teamId) {
        var response = http().post().uri("/api/runbooks").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(runbookParts(teamId, true)).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).as("create runbook").isEqualTo(201);
        return response.getBody().get("id").asLong();
    }

    /** Status of a multipart runbook create ({@code POST}, with a file) or update ({@code PUT}, metadata only). */
    private int uploadStatus(String method, String uri, String token, Long teamId) {
        return http().method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(runbookParts(teamId, "POST".equals(method)))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private static MultiValueMap<String, Object> runbookParts(Long teamId, boolean withFile) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        if (withFile) {
            parts.add("file", new ByteArrayResource("# Steps".getBytes(StandardCharsets.UTF_8)) {
                @Override
                public String getFilename() {
                    return "steps.md";
                }
            });
        }
        if (teamId != null) {
            parts.add("teamId", teamId.toString());
        }
        return parts;
    }

    private int status(String method, String uri, String token, Object body) {
        var spec = http().method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
                .headers(h -> { if (token != null) h.setBearerAuth(token); });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(new HashMap<>((Map<?, ?>) body));
        }
        return spec.retrieve().toBodilessEntity().getStatusCode().value();
    }
}
