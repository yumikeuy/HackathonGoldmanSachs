package com.hackathon.alerting.delivery.provider;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * UI channel: the alert already reached the team feed over WebSocket when it was created (Role 2's push),
 * so the outbox row only records that the team was notified.
 */
@Component
public class UiFeedProvider implements NotificationProvider {

    @Override
    public Channel channel() {
        return Channel.UI;
    }

    @Override
    public void send(OutboxTask task, JsonNode payload) {
        // nothing to send
    }
}
