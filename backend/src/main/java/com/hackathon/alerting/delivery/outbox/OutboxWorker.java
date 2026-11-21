package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.common.config.WebSocketTopics;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.provider.NotificationProvider;
import com.hackathon.alerting.delivery.ratelimit.RateLimiter;
import com.hackathon.alerting.delivery.simulator.ChannelSwitchboard;
import com.hackathon.alerting.delivery.simulator.Delivery;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the {@code delivery_outbox} every 500 ms (AGENTS.md §6).
 *
 * <p>Each run locks up to {@value #BATCH_SIZE} due PENDING rows ({@code FOR UPDATE SKIP LOCKED}, so several instances
 * can run side by side) and, in the same transaction, sends them and records the outcome:
 * <ul>
 *   <li>success → SENT;</li>
 *   <li>failure (provider error or channel switched off in the simulator) → retry after 2, 5, 15, 60 s, then FAILED;</li>
 *   <li>rate limit: at most {@value RateLimiter#LIMIT} messages per user and channel per minute. If a user has more due
 *       messages than permits, the last permit is spent on one digest of all the excess; with no permits left the
 *       rows wait for the window to reset (no retry counted).</li>
 * </ul>
 * Every attempt is pushed to {@code /topic/simulator} after the commit.
 */
@Component
public class OutboxWorker {

    static final int BATCH_SIZE = 100;
    static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60));
    static final int MAX_ERROR_LENGTH = 1000;

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxTaskRepository outbox;
    private final Map<Channel, NotificationProvider> providers = new EnumMap<>(Channel.class);
    private final ChannelSwitchboard switchboard;
    private final RateLimiter rateLimiter;
    private final SimpMessagingTemplate websocket;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutboxWorker(OutboxTaskRepository outbox, List<NotificationProvider> providers, ChannelSwitchboard switchboard,
                        RateLimiter rateLimiter, SimpMessagingTemplate websocket, TransactionTemplate transaction, Clock clock) {
        this.outbox = outbox;
        providers.forEach(provider -> this.providers.put(provider.channel(), provider));
        this.switchboard = switchboard;
        this.rateLimiter = rateLimiter;
        this.websocket = websocket;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${alerting.outbox.poll-interval:500ms}")
    public void run() {
        try {
            List<Delivery> attempts = transaction.execute(status -> processBatch());
            if (attempts != null) {
                attempts.forEach(delivery -> websocket.convertAndSend(WebSocketTopics.SIMULATOR, delivery));
            }
        } catch (RuntimeException e) {
            log.warn("Outbox run failed, will retry: {}", e.getMessage());
        }
    }

    /** One batch inside a transaction; returns the attempts made. */
    List<Delivery> processBatch() {
        Instant now = clock.instant();
        List<OutboxTask> due = outbox.lockDueBatch(now, BATCH_SIZE);
        if (due.isEmpty()) {
            return List.of();
        }
        List<OutboxTask> attempted = new ArrayList<>();
        Map<RecipientChannel, List<OutboxTask>> personal = new LinkedHashMap<>();
        for (OutboxTask task : due) {
            if (task.getUser() == null) {
                attempt(task, task.getPayload(), now);
                attempted.add(task);
            } else {
                personal.computeIfAbsent(new RecipientChannel(task.getUser().getId(), task.getChannel()), k -> new ArrayList<>()).add(task);
            }
        }
        personal.forEach((recipient, tasks) -> attempted.addAll(sendRateLimited(recipient, tasks, now)));
        return attempted.stream().map(Delivery::from).toList();
    }

    private List<OutboxTask> sendRateLimited(RecipientChannel recipient, List<OutboxTask> tasks, Instant now) {
        if (!switchboard.isEnabled(recipient.channel())) {
            // Do not spend permits on a channel that is down.
            tasks.forEach(task -> fail(task, "Channel " + recipient.channel() + " is down (simulator)", now));
            return tasks;
        }
        RateLimiter.Permits permits = rateLimiter.acquire(recipient.userId(), recipient.channel(), tasks.size());
        if (permits.granted() == 0) {
            Instant later = now.plus(permits.windowReset());
            tasks.forEach(task -> task.postpone(later));
            return List.of();
        }
        if (permits.granted() >= tasks.size()) {
            tasks.forEach(task -> attempt(task, task.getPayload(), now));
            return tasks;
        }
        int individually = permits.granted() - 1;
        tasks.subList(0, individually).forEach(task -> attempt(task, task.getPayload(), now));
        List<OutboxTask> excess = tasks.subList(individually, tasks.size());
        sendDigest(excess, now);
        return tasks;
    }

    /** Sends one message summarising {@code tasks} through the first of them; all share the outcome. */
    private void sendDigest(List<OutboxTask> tasks, Instant now) {
        OutboxTask carrier = tasks.getFirst();
        ObjectNode digest = digest(tasks);
        String error = trySend(carrier, digest);
        for (OutboxTask task : tasks) {
            if (error == null) {
                if (task != carrier) {
                    ObjectNode merged = ((ObjectNode) task.getPayload()).deepCopy();
                    merged.put("mergedInto", carrier.getId());
                    task.setPayload(merged);
                }
                task.markSent(now);
            } else {
                fail(task, error, now);
            }
        }
        if (error == null) {
            carrier.setPayload(digest);
        }
    }

    static ObjectNode digest(List<OutboxTask> tasks) {
        JsonNode first = tasks.getFirst().getPayload();
        ArrayNode alertIds = JsonNodeFactory.instance.arrayNode();
        StringBuilder text = new StringBuilder(tasks.size() + " alerts (rate limit reached, merged):");
        for (OutboxTask task : tasks) {
            JsonNode payload = task.getPayload();
            alertIds.add(payload.path("alertId").asLong());
            text.append("\n- [").append(payload.path("severity").asString()).append("] ").append(payload.path("title").asString());
        }
        ObjectNode digest = JsonNodeFactory.instance.objectNode();
        if (first.has("to")) {
            digest.put("to", first.path("to").asString());
        }
        digest.put("subject", tasks.size() + " alerts (merged)");
        digest.put("text", text.toString());
        digest.put("merged", tasks.size());
        digest.set("alertIds", alertIds);
        return digest;
    }

    private void attempt(OutboxTask task, JsonNode payload, Instant now) {
        String error = trySend(task, payload);
        if (error == null) {
            task.markSent(now);
        } else {
            fail(task, error, now);
        }
    }

    /** @return {@code null} on success, otherwise the error message */
    private String trySend(OutboxTask task, JsonNode payload) {
        if (!switchboard.isEnabled(task.getChannel())) {
            return "Channel " + task.getChannel() + " is down (simulator)";
        }
        NotificationProvider provider = providers.get(task.getChannel());
        if (provider == null) {
            return "No provider for channel " + task.getChannel();
        }
        try {
            provider.send(task, payload);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void fail(OutboxTask task, String error, Instant now) {
        String message = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (task.getRetryCount() < RETRY_DELAYS.size()) {
            task.scheduleRetry(message, now.plus(RETRY_DELAYS.get(task.getRetryCount())));
        } else {
            task.markFailed(message);
            log.warn("Delivery {} over {} failed permanently: {}", task.getId(), task.getChannel(), message);
        }
    }

    private record RecipientChannel(long userId, Channel channel) {
    }
}
