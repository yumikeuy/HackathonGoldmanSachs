package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.common.security.AuthSettings;
import com.hackathon.alerting.common.security.RunbookLinkSigner;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.rules.model.Alert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * Renders the {@code delivery_outbox.payload} of a notification — the finished message for its channel, so the
 * worker only has to send it. The runbook is a link to {@code GET /api/runbooks/{id}/file}, never an attachment;
 * with login enforced the link is signed and works for {@link #RUNBOOK_LINK_TTL} without a token
 * ({@link RunbookLinkSigner}).
 *
 * <p>Payload fields: {@code alertId}, {@code severity}, {@code title}, {@code text}, {@code firstSeenAt} (for the
 * delivery latency metric), {@code runbookUrl} (if any);
 * EMAIL adds {@code to} and {@code subject}, SMS and CALL add {@code to} (the phone number).
 */
@Component
public class MessageComposer {

    /** SMS texts are cut to this length. */
    static final int SMS_MAX_LENGTH = 320;

    /** How long a runbook link in a notification works without logging in. */
    static final Duration RUNBOOK_LINK_TTL = Duration.ofDays(7);

    private final String publicUrl;
    private final RunbookLinkSigner links;
    private final AuthSettings auth;

    public MessageComposer(@Value("${alerting.public-url:http://localhost:8080}") String publicUrl, RunbookLinkSigner links,
                           AuthSettings auth) {
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.links = links;
        this.auth = auth;
    }

    /** The team feed row of the UI channel. */
    public ObjectNode forTeamFeed(Alert alert) {
        return base(alert);
    }

    /** A personal message to {@code user} over {@code channel}. */
    public ObjectNode forUser(Alert alert, User user, Channel channel) {
        ObjectNode payload = base(alert);
        String runbookUrl = runbookUrl(alert);
        String headline = "[" + alert.getSeverity() + "] " + alert.getTitle();
        switch (channel) {
            case EMAIL -> {
                payload.put("to", user.getEmail());
                payload.put("subject", headline);
                payload.put("text", "Hello " + user.getName() + ",\n\n"
                        + "Alert #" + alert.getId() + ": " + alert.getTitle() + "\n"
                        + "Severity: " + alert.getSeverity() + "\n"
                        + "Team: " + alert.getTeam().getName() + "\n"
                        + "First seen: " + alert.getFirstSeenAt() + "\n"
                        + (runbookUrl == null ? "" : "Runbook: " + runbookUrl + "\n")
                        + "\nOpen the alert feed to acknowledge it.");
            }
            case SMS, CALL -> {
                payload.put("to", user.getPhone());
                String text = headline + (runbookUrl == null ? "" : " Runbook: " + runbookUrl);
                payload.put("text", channel == Channel.SMS && text.length() > SMS_MAX_LENGTH ? text.substring(0, SMS_MAX_LENGTH) : text);
            }
            case UI -> throw new IllegalArgumentException("UI is not a personal channel");
        }
        return payload;
    }

    private ObjectNode base(Alert alert) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("alertId", alert.getId())
                .put("severity", alert.getSeverity().name())
                .put("title", alert.getTitle())
                .put("text", "[" + alert.getSeverity() + "] " + alert.getTitle())
                .put("firstSeenAt", alert.getFirstSeenAt().toString());
        String runbookUrl = runbookUrl(alert);
        if (runbookUrl != null) {
            payload.put("runbookUrl", runbookUrl);
        }
        return payload;
    }

    private String runbookUrl(Alert alert) {
        if (alert.getRunbook() == null) {
            return null;
        }
        long id = alert.getRunbook().getId();
        // With login enforced a link in an email has no token: sign it. Otherwise the file is open anyway.
        return publicUrl + (auth.enabled() ? links.sign(id, RUNBOOK_LINK_TTL).url() : "/api/runbooks/" + id + "/file");
    }
}
