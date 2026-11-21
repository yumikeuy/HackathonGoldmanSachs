package com.hackathon.alerting.delivery.provider.call;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.provider.NotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Voice call simulator: "calls" by logging the text it would read out. */
@Component
public class CallProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(CallProvider.class);

    @Override
    public Channel channel() {
        return Channel.CALL;
    }

    @Override
    public void send(OutboxTask task, JsonNode payload) {
        log.info("CALL to {}: {}", payload.path("to").asString(), payload.path("text").asString());
    }
}
