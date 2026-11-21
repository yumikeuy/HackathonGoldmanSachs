package com.hackathon.alerting.delivery.provider.email;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.provider.NotificationProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** EMAIL over SMTP — MailHog in development (web UI on :8025). Uses {@code to}, {@code subject}, {@code text} of the payload. */
@Component
public class EmailProvider implements NotificationProvider {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailProvider(JavaMailSender mailSender, @Value("${alerting.mail.from:alerts@alerting.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public void send(OutboxTask task, JsonNode payload) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(payload.path("to").asString());
        message.setSubject(payload.path("subject").asString());
        message.setText(payload.path("text").asString());
        mailSender.send(message);
    }
}
