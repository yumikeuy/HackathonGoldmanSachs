package com.hackathon.alerting.ingest;

import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.model.EventStatus;
import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.repository.EventRepository;
import com.hackathon.alerting.ingest.repository.SourceRepository;
import com.hackathon.alerting.support.JpaRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.hackathon.alerting.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JpaRepositoryTest
class IngestRepositoriesTest {

    @Autowired
    private SourceRepository sources;

    @Autowired
    private EventRepository events;

    @Autowired
    private TestEntityManager em;

    @Test
    void findsSourcesByNameAndActiveKafkaTopics() {
        Source http = sources.save(new Source("http-" + UUID.randomUUID(), SourceType.HTTP, null));
        Source kafka = sources.save(new Source("kafka-" + UUID.randomUUID(), SourceType.KAFKA, json("{\"topic\":\"t\"}")));
        Source disabled = new Source("off-" + UUID.randomUUID(), SourceType.KAFKA, null);
        disabled.setActive(false);
        sources.save(disabled);

        assertThat(sources.findByName(http.getName())).contains(http);
        assertThat(sources.existsByName(kafka.getName())).isTrue();
        assertThat(sources.findAllByTypeAndActiveTrueOrderByIdAsc(SourceType.KAFKA)).contains(kafka).doesNotContain(disabled);
    }

    @Test
    void savingTheSameEventTwiceFailsInsteadOfOverwriting() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        events.saveAndFlush(Event.failed(id, "src", now, json("{\"a\":1}"), "first"));
        em.clear();

        assertThatThrownBy(() -> events.saveAndFlush(Event.failed(id, "src", now, json("{\"a\":2}"), "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pagesFailedEventsNewestFirstByKeyset() {
        Instant base = Instant.parse("2030-01-01T00:00:00Z");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            // two events share a timestamp to exercise the event_id tie-breaker
            events.save(Event.failed(id, "src", base.plusSeconds(i / 2), json("{}"), "boom"));
            expected.add(id);
        }
        events.save(Event.unrouted(UUID.randomUUID(), "src", base.plusSeconds(10), json("{}"), null));
        events.flush();

        List<Event> seen = new ArrayList<>();
        ScrollPosition position = ScrollPosition.keyset();
        Window<Event> page;
        do {
            page = events.findByStatusOrderByIngestedAtDescEventIdDesc(EventStatus.FAILED, position, Limit.of(2));
            seen.addAll(page.getContent());
            if (!page.isEmpty()) {
                position = page.positionAt(page.size() - 1);
            }
        } while (page.hasNext());

        // every FAILED event exactly once, newest first; the UNROUTED one is excluded
        assertThat(seen).extracting(Event::getEventId).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen).extracting(Event::getIngestedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
