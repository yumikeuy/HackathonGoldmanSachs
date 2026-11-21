package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * STEP-0 STUB of {@link OutboxService} (owner Role 1): inserts the single UI row for the alert's team and
 * nothing for personal channels. Role 1 replaces the body with routing by user preferences.
 */
@Service
public class DefaultOutboxService implements OutboxService {

    private final OutboxTaskRepository outbox;

    public DefaultOutboxService(OutboxTaskRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public void enqueue(Alert alert, Rule rule) {
        if (alert.getTeam() == null) {
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("alertId", alert.getId())
                .put("title", alert.getTitle())
                .put("severity", alert.getSeverity().name());
        outbox.save(OutboxTask.toTeamFeed(alert, alert.getTeam(), payload));
    }
}
