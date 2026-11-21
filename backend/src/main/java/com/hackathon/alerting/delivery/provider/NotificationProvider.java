package com.hackathon.alerting.delivery.provider;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import tools.jackson.databind.JsonNode;

/**
 * Sends one notification over one channel. Called by the {@code OutboxWorker}; a thrown exception is a failed
 * attempt that will be retried (2, 5, 15, 60 s), so implementations must not swallow errors.
 */
public interface NotificationProvider {

    Channel channel();

    /**
     * @param task    the outbox row (recipient, channel)
     * @param payload the message to send — normally {@code task.getPayload()}, or a digest of several rows
     *                merged by the rate limiter
     * @throws Exception if the message was not delivered
     */
    void send(OutboxTask task, JsonNode payload) throws Exception;
}
