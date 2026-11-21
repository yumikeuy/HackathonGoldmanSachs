package com.hackathon.alerting.delivery.provider.sms;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.provider.NotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** SMS simulator: "sends" by logging; the attempt is visible in the delivery simulator. */
@Component
public class SmsProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsProvider.class);

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public void send(OutboxTask task, JsonNode payload) {
        log.info("SMS to {}: {}", payload.path("to").asString(), payload.path("text").asString());
    }
}
