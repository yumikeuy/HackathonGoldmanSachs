package com.hackathon.alerting.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Live updates across instances: every message pushed to a {@code /topic/**} destination on one instance (alerts,
 * UNROUTED, simulator — whoever sends it with {@code SimpMessagingTemplate}) is also published to the Redis channel
 * {@value #CHANNEL}, and every other instance hands it to its own in-memory broker. So a browser connected to
 * instance B sees an alert that instance A created. No external STOMP broker to run; senders do not change.
 *
 * <p>Registered as an interceptor of the broker channel ({@link WebSocketConfig}). Relayed messages carry the
 * {@value #ORIGIN_HEADER} header and are not published again. If Redis is down, local subscribers still get their
 * instance's messages.
 */
@Component
public class WebSocketFanout implements ChannelInterceptor, SmartLifecycle {

    static final String CHANNEL = "ws.fanout";
    static final String ORIGIN_HEADER = "alertingFanoutOrigin";

    private static final Logger log = LoggerFactory.getLogger(WebSocketFanout.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final ObjectProvider<MessageChannel> brokerChannel;
    private final RedisChannelSubscription subscription;
    private final String instanceId = UUID.randomUUID().toString();

    public WebSocketFanout(StringRedisTemplate redis, JsonMapper json, RedisConnectionFactory connectionFactory,
                           @Qualifier("brokerChannel") ObjectProvider<MessageChannel> brokerChannel) {
        this.redis = redis;
        this.json = json;
        this.brokerChannel = brokerChannel;
        this.subscription = new RedisChannelSubscription(connectionFactory, CHANNEL,
                (message, pattern) -> relay(new String(message.getBody(), StandardCharsets.UTF_8)));
    }

    /** Outgoing local broadcast → also to Redis. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        String destination = SimpMessageHeaderAccessor.getDestination(headers);
        if (destination == null || !destination.startsWith(WebSocketTopics.PREFIX + "/")
                || SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE
                || headers.containsKey(ORIGIN_HEADER) || !(message.getPayload() instanceof byte[] body)) {
            return message;
        }
        try {
            ObjectNode envelope = json.createObjectNode()
                    .put("origin", instanceId)
                    .put("destination", destination)
                    .put("payload", Base64.getEncoder().encodeToString(body));
            Object contentType = headers.get(MessageHeaders.CONTENT_TYPE);
            if (contentType != null) {
                envelope.put("contentType", contentType.toString());
            }
            redis.convertAndSend(CHANNEL, json.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.debug("Could not fan out {} to other instances: {}", destination, e.getMessage());
        }
        return message;
    }

    /** Broadcast from another instance → our local subscribers. */
    void relay(String text) {
        JsonNode envelope = json.readTree(text);
        if (instanceId.equals(envelope.path("origin").asString())) {
            return;
        }
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(envelope.path("destination").asString());
        if (envelope.hasNonNull("contentType")) {
            accessor.setContentType(MimeType.valueOf(envelope.get("contentType").asString()));
        }
        accessor.setHeader(ORIGIN_HEADER, envelope.path("origin").asString());
        accessor.setLeaveMutable(true);
        byte[] payload = Base64.getDecoder().decode(envelope.path("payload").asString());
        brokerChannel.getObject().send(MessageBuilder.createMessage(payload, accessor.getMessageHeaders()));
    }

    @Override
    public void start() {
        subscription.start();
    }

    @Override
    public void stop() {
        subscription.stop();
    }

    @Override
    public boolean isRunning() {
        return subscription.isRunning();
    }
}
