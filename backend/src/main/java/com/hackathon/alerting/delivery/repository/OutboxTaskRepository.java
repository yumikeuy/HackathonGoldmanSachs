package com.hackathon.alerting.delivery.repository;

import com.hackathon.alerting.delivery.model.OutboxStatus;
import com.hackathon.alerting.delivery.model.OutboxTask;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/** Access to {@code delivery_outbox}. */
public interface OutboxTaskRepository extends JpaRepository<OutboxTask, Long> {

    /**
     * Locks the next batch of due PENDING rows for the {@code OutboxWorker}.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several worker threads or instances run concurrently
     * without picking the same row; rows stay locked until the calling transaction ends, so this
     * must run inside one. Served by the partial index {@code idx_outbox_pending}.
     *
     * @param now   current time; rows with {@code next_retry_at <= now} are due
     * @param limit maximum batch size (AGENTS.md: 100)
     */
    @Query(value = """
            SELECT * FROM delivery_outbox
            WHERE status = 'PENDING' AND next_retry_at <= :now
            ORDER BY next_retry_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxTask> lockDueBatch(Instant now, int limit);

    /** Number of rows in a status ({@code alerting.delivery.pending} gauge). */
    long countByStatus(OutboxStatus status);

    /** Rows of one alert, oldest first (alert card, simulator). */
    List<OutboxTask> findAllByAlertIdOrderByIdAsc(Long alertId);

    /**
     * Keyset page of all deliveries, newest first ({@code GET /api/simulator/deliveries}).
     *
     * @param position {@code ScrollPosition.keyset()} for the first page, then {@code window.positionAt(last)}
     */
    Window<OutboxTask> findAllByOrderByIdDesc(ScrollPosition position, Limit limit);

    /**
     * Hourly cleanup: deletes SENT rows sent before {@code cutoff} (AGENTS.md: older than 1 day).
     * Served by the partial index {@code idx_outbox_sent}.
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query(value = "DELETE FROM delivery_outbox WHERE status = 'SENT' AND sent_at < :cutoff", nativeQuery = true)
    int deleteSentBefore(Instant cutoff);
}
