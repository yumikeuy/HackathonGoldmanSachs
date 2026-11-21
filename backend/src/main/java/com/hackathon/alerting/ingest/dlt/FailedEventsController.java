package com.hackathon.alerting.ingest.dlt;

import com.hackathon.alerting.common.web.CursorPage;
import com.hackathon.alerting.common.web.Cursors;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.model.EventStatus;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.repository.EventRepository;
import com.hackathon.alerting.ingest.source.EventIngestor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Failed events: {@code GET /api/events/failed} (keyset, newest first) and
 * {@code POST /api/events/{eventId}/replay}.
 *
 * <p>A replay sends the original payload and ingestion time back to {@code events.ingest} under a <b>new</b>
 * {@code event_id} (the old one is already marked as seen by the processor) and, once Kafka acknowledged it,
 * deletes the FAILED row; if processing fails again, the DLT listener stores a new one. An UNROUTED event can be
 * replayed too (e.g. after adding a matching team) — its row stays, since it belongs to the UNROUTED alert.
 */
@RestController
public class FailedEventsController {

    private final EventRepository events;
    private final EventIngestor ingestor;

    public FailedEventsController(EventRepository events, EventIngestor ingestor) {
        this.events = events;
        this.ingestor = ingestor;
    }

    @GetMapping("/api/events/failed")
    @Transactional(readOnly = true)
    public CursorPage<FailedEvent> failed(@RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit) {
        return CursorPage.of(
                events.findByStatusOrderByIngestedAtDescEventIdDesc(EventStatus.FAILED, Cursors.decode(cursor), Cursors.limit(limit)),
                FailedEvent::from);
    }

    @PostMapping("/api/events/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void replay(@PathVariable UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        ingestor.send(new EventEnvelope(UUID.randomUUID(), event.getIngestedAt(), SourceType.HTTP,
                event.getSourceName(), event.getRawPayload()));
        if (event.getStatus() == EventStatus.FAILED) {
            events.delete(event);
        }
    }

    /** {@code FailedEvent} of openapi.yaml. */
    public record FailedEvent(UUID eventId, String sourceName, Instant ingestedAt, EventStatus status, String error,
                              JsonNode rawPayload, Long alertId) {

        static FailedEvent from(Event event) {
            return new FailedEvent(event.getEventId(), event.getSourceName(), event.getIngestedAt(), event.getStatus(),
                    event.getError(), event.getRawPayload(), event.getAlert() == null ? null : event.getAlert().getId());
        }
    }
}
