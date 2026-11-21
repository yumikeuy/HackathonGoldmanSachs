package com.hackathon.alerting.delivery.simulator;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxStatus;
import com.hackathon.alerting.delivery.model.OutboxTask;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** {@code Delivery} of openapi.yaml: one {@code delivery_outbox} row; also the body of {@code /topic/simulator}. */
public record Delivery(long id, long alertId, long teamId, Long userId, Channel channel, OutboxStatus status,
                       int retryCount, String lastError, JsonNode payload, Instant createdAt, Instant nextRetryAt,
                       Instant sentAt) {

    public static Delivery from(OutboxTask task) {
        return new Delivery(task.getId(), task.getAlert().getId(), task.getTeam().getId(),
                task.getUser() == null ? null : task.getUser().getId(), task.getChannel(), task.getStatus(),
                task.getRetryCount(), task.getLastError(), task.getPayload(), task.getCreatedAt(), task.getNextRetryAt(),
                task.getSentAt());
    }
}
