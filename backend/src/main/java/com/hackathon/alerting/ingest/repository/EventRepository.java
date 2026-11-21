package com.hackathon.alerting.ingest.repository;

import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.model.EventStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Access to {@code events} (unrouted and failed events only).
 *
 * <p>Saving a new {@link Event} always issues an INSERT ({@link Event#isNew()}), so a second
 * save of the same {@code event_id} fails on the primary key instead of overwriting the first.
 */
public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * Keyset page of events with the given status, newest first ({@code GET /api/events/failed}).
     * Served by index {@code idx_events_status_time}.
     *
     * @param position {@code ScrollPosition.keyset()} for the first page, then {@code window.positionAt(last)}
     * @param limit    page size (AGENTS.md: 50)
     */
    Window<Event> findByStatusOrderByIngestedAtDescEventIdDesc(EventStatus status, ScrollPosition position, Limit limit);
}
