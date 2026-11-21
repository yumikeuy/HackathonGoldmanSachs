package com.hackathon.alerting.ingest;

import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.ingest.model.EventStatus;
import com.hackathon.alerting.ingest.recent.RecentEvents;
import com.hackathon.alerting.ingest.repository.EventRepository;
import com.hackathon.alerting.ingest.source.FileTailSources;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Role 1 "done when": an event via HTTP and via an external Kafka topic arrives in {@code events.ingest};
 * failed events are stored from the DLT and can be replayed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class IngestIntegrationTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private KafkaAdmin kafkaAdmin;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private JsonMapper json;
    @Autowired
    private RecentEvents recentEvents;
    @Autowired
    private EventRepository events;
    @Autowired
    private FileTailSources fileSources;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void httpEventIsAcknowledgedOnlyAfterKafkaAndLandsInEventsIngest() {
        String source = "http-" + UUID.randomUUID();
        createSource(Map.of("name", source, "type", "http"));
        String marker = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> accepted = http().post().uri("/api/ingest/{s}", source).contentType(MediaType.APPLICATION_JSON)
                .body("{\"trade\":{\"id\":\"" + marker + "\",\"notional\":15000000},\"status\":\"REJECTED\"}")
                .retrieve().toEntity(JsonNode.class);

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String eventId = accepted.getBody().get("eventId").asString();
        JsonNode envelope = awaitEnvelope(e -> e.get("event_id").asString().equals(eventId));
        assertThat(envelope.get("source_name").asString()).isEqualTo(source);
        assertThat(envelope.get("source_type").asString()).isEqualTo("http");
        assertThat(envelope.at("/raw_payload/trade/id").asString()).isEqualTo(marker);

        assertThat(recentEvents.latest(10)).anyMatch(e -> marker.equals(e.at("/trade/id").asString()));
        JsonNode fields = http().get().uri("/api/fields").retrieve().body(JsonNode.class);
        assertThat(fields.findValuesAsString("path")).contains("trade.notional", "status");
    }

    @Test
    void rejectsInvalidJsonAndUnknownSources() {
        String source = "http-" + UUID.randomUUID();
        createSource(Map.of("name", source, "type", "http"));

        assertThat(http().post().uri("/api/ingest/{s}", source).contentType(MediaType.APPLICATION_JSON).body("{not json")
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(400);
        assertThat(http().post().uri("/api/ingest/{s}", "no-such-source").contentType(MediaType.APPLICATION_JSON).body("{}")
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void externalKafkaTopicIsForwardedToEventsIngest() throws Exception {
        String source = "kafka-" + UUID.randomUUID();
        String topic = "external." + UUID.randomUUID();
        createSource(Map.of("name", source, "type", "kafka", "config", Map.of("topic", topic)));
        String marker = UUID.randomUUID().toString();

        // The listener starts after the config.changed round trip through Redis; keep producing until it picks up.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            kafka.send(topic, "{\"workflow\":\"pricing\",\"marker\":\"" + marker + "\"}").get(10, TimeUnit.SECONDS);
            JsonNode envelope = awaitEnvelope(e -> e.get("source_name").asString().equals(source), Duration.ofSeconds(5));
            assertThat(envelope.get("source_type").asString()).isEqualTo("kafka");
            assertThat(envelope.at("/raw_payload/marker").asString()).isEqualTo(marker);
        });
    }

    @Test
    void linesAppendedToAFileSourceArriveInOrder(@TempDir Path dir) throws Exception {
        String source = "file-" + UUID.randomUUID();
        Path log = dir.resolve("app.log");
        Files.writeString(log, "{\"before\":\"start\"}\n");
        createSource(Map.of("name", source, "type", "file", "config", Map.of("path", log.toString())));
        await().atMost(Duration.ofSeconds(30)).until(() -> fileSources.isTailing(source));

        Files.writeString(log, "{\"level\":\"ERROR\",\"n\":1}\nplain text line\n", StandardOpenOption.APPEND);

        JsonNode first = awaitEnvelope(e -> e.get("source_name").asString().equals(source));
        assertThat(first.get("source_type").asString()).isEqualTo("file");
        assertThat(first.at("/raw_payload/n").asInt()).isEqualTo(1);
        JsonNode second = awaitEnvelope(e -> e.get("source_name").asString().equals(source)
                && e.at("/raw_payload/message").asString().equals("plain text line"));
        assertThat(second).isNotNull();
        assertThat(http().post().uri("/api/sources").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "file-" + UUID.randomUUID(), "type", "file")).retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deadLettersAreStoredAsFailedEventsAndCanBeReplayed() throws Exception {
        UUID eventId = UUID.randomUUID();
        String envelope = """
                {"event_id":"%s","ingested_at":"2026-11-21T00:17:03.120Z","source_type":"http",
                 "source_name":"payments","raw_payload":{"amount":42}}""".formatted(eventId);
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopics.EVENTS_INGEST_DLT, "payments", envelope);
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "boom".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(60)).until(() -> events.findById(eventId).isPresent());
        assertThat(events.findById(eventId).get().getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(events.findById(eventId).get().getError()).isEqualTo("boom");
        JsonNode page = http().get().uri("/api/events/failed?limit=200").retrieve().body(JsonNode.class);
        assertThat(page.get("items").findValuesAsString("eventId")).contains(eventId.toString());

        assertThat(http().post().uri("/api/events/{id}/replay", eventId).retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(202);
        assertThat(events.findById(eventId)).isEmpty();
        JsonNode replayed = awaitEnvelope(e -> e.at("/raw_payload/amount").asInt() == 42
                && e.get("source_name").asString().equals("payments")
                && !e.get("event_id").asString().equals(eventId.toString()));
        assertThat(replayed.get("ingested_at").asString()).isEqualTo("2026-11-21T00:17:03.120Z");

        assertThat(http().post().uri("/api/events/{id}/replay", UUID.randomUUID()).retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void reportsConsumerLagOfAllSixPartitions() {
        JsonNode lag = http().get().uri("/api/ingest/lag").retrieve().body(JsonNode.class);

        assertThat(lag.get("groupId").asString()).isEqualTo(KafkaTopics.PROCESSOR_GROUP);
        assertThat(lag.get("partitions")).hasSize(6);
    }

    private void createSource(Map<String, Object> body) {
        int status = http().post().uri("/api/sources").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(201);
    }

    private JsonNode awaitEnvelope(Predicate<JsonNode> match) {
        return awaitEnvelope(match, Duration.ofSeconds(30));
    }

    /** Reads {@code events.ingest} from the beginning with a throw-away group until an envelope matches. */
    private JsonNode awaitEnvelope(Predicate<JsonNode> match, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.EVENTS_INGEST));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (envelope.has("event_id") && match.test(envelope)) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No matching envelope in " + KafkaTopics.EVENTS_INGEST + " within " + timeout);
    }
}
