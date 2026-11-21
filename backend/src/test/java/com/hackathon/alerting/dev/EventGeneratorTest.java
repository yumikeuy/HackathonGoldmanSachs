package com.hackathon.alerting.dev;

import com.hackathon.alerting.common.web.ConflictException;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.ServiceUnavailableException;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.source.EventIngestor;
import com.hackathon.alerting.ingest.source.SourceCache;
import com.hackathon.alerting.ingest.source.SourceCache.CachedSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The generator against a mocked ingest path: validation, pacing loop, concurrency cap, stop on Kafka outage. */
class EventGeneratorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CachedSource SOURCE = new CachedSource(1, "gen-src", SourceType.HTTP, JSON.createObjectNode(), true);

    private final SourceCache sources = mock(SourceCache.class);
    private final EventIngestor ingestor = mock(EventIngestor.class);
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    private final EventGenerator generator;

    EventGeneratorTest() throws IOException {
        when(sources.findActive("gen-src")).thenReturn(Optional.of(SOURCE));
        when(sources.findActive("missing")).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(2));
            return null;
        }).when(ingestor).ingest(eq(SourceType.HTTP), eq("gen-src"), any());
        generator = new EventGenerator(sources, ingestor, new SampleEvents(JSON), new TemplateExpander(Clock.systemUTC()));
    }

    @AfterEach
    void stop() {
        generator.shutdown();
    }

    @Test
    void sendsTheRequestedNumberOfExpandedEvents() {
        GenerateAccepted accepted = generator.start(new GenerateRequest("gen-src", 5, 1000, JSON.readTree("{\"id\":\"{{seq}}\"}")));

        assertThat(accepted.count()).isEqualTo(5);
        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 5);
        assertThat(sent).extracting(event -> event.get("id").asInt()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void cyclesBuiltInSamplesWithoutATemplate() throws IOException {
        SampleEvents samples = new SampleEvents(JSON);

        generator.start(new GenerateRequest("gen-src", samples.size() + 1, 1000, null));

        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == samples.size() + 1);
        assertThat(sent.getLast()).isEqualTo(samples.get(0));
    }

    @Test
    void rejectsUnknownSourcesAndInvalidTemplates() {
        assertThatThrownBy(() -> generator.start(new GenerateRequest("missing", 1, 1, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> generator.start(new GenerateRequest("gen-src", 1, 1, JSON.readTree("{\"a\":\"{{nope}}\"}"))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void limitsConcurrentRuns() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await();
            return null;
        }).when(ingestor).ingest(any(), any(), any());

        for (int i = 0; i < EventGenerator.MAX_CONCURRENT_RUNS; i++) {
            generator.start(new GenerateRequest("gen-src", 1, 1, null));
        }
        assertThatThrownBy(() -> generator.start(new GenerateRequest("gen-src", 1, 1, null)))
                .isInstanceOf(ConflictException.class);

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(generator.start(new GenerateRequest("gen-src", 1, 1000, null)).count()).isEqualTo(1));
    }

    @Test
    void stopsTheRunWhenKafkaIsUnavailable() {
        List<Integer> attempts = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            attempts.add(1);
            throw new ServiceUnavailableException("Kafka is unavailable, retry later", null);
        }).when(ingestor).ingest(any(), any(), any());

        generator.start(new GenerateRequest("gen-src", 50, 1000, null));

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> attempts.size() == 1);
    }
}
