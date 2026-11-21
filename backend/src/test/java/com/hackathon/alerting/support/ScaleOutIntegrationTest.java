package com.hackathon.alerting.support;

import com.hackathon.alerting.common.config.WebSocketTopics;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.simulator.ChannelSwitchboard;
import com.hackathon.alerting.ingest.source.FileSourceLeases;
import com.hackathon.alerting.ingest.source.FileTailer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Several instances behind a load balancer share state through Redis (docs/operations.md, "Several instances").
 * The "other instance" is simulated with a second component instance or a message from a foreign origin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class ScaleOutIntegrationTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisConnectionFactory connectionFactory;
    @Autowired
    private SimpMessagingTemplate websocket;
    @Autowired
    private ChannelSwitchboard switchboard;

    @Test
    void broadcastsFromAnotherInstanceReachLocalSubscribers() throws Exception {
        String topic = WebSocketTopics.PREFIX + "/fanout-" + UUID.randomUUID();
        BlockingQueue<String> received = subscribe(topic);

        String envelope = """
                {"origin":"other-instance","destination":"%s","contentType":"text/plain;charset=UTF-8","payload":"%s"}"""
                .formatted(topic, Base64.getEncoder().encodeToString("{\"id\":42}".getBytes(StandardCharsets.UTF_8)));
        // the subscription is asynchronous: publish until the message arrives
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).until(() -> {
            redis.convertAndSend("ws.fanout", envelope);
            return received.poll(400, TimeUnit.MILLISECONDS) != null;
        });
    }

    @Test
    void localBroadcastsArePublishedForOtherInstances() {
        String topic = WebSocketTopics.PREFIX + "/fanout-" + UUID.randomUUID();
        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener((message, pattern) -> published.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic("ws.fanout"));
        listener.afterPropertiesSet();
        listener.start();
        try {
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).until(() -> {
                websocket.convertAndSend(topic, (Object) Map.of("alertId", 7));
                return published.stream().anyMatch(m -> m.contains(topic));
            });
        } finally {
            listener.stop();
        }
    }

    @Test
    void simulatorSwitchesApplyToEveryInstance() {
        ChannelSwitchboard otherInstance = new ChannelSwitchboard(redis);
        boolean before = otherInstance.isEnabled(Channel.CALL);

        switchboard.toggle(Channel.CALL);
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> otherInstance.isEnabled(Channel.CALL) != before);
        } finally {
            switchboard.toggle(Channel.CALL);
        }
    }

    @Test
    void onlyOneInstanceTailsAFileAndTheNextContinuesFromTheCheckpoint() {
        FileSourceLeases instanceA = new FileSourceLeases(redis);
        FileSourceLeases instanceB = new FileSourceLeases(redis);
        String source = "lease-" + UUID.randomUUID();

        assertThat(instanceA.hold(source)).isTrue();
        assertThat(instanceA.hold(source)).as("renewal").isTrue();
        assertThat(instanceB.hold(source)).isFalse();

        instanceA.saveCheckpoint(source, new FileTailer.Checkpoint("(dev=1,ino=2)", 128));
        instanceA.release(source);

        assertThat(instanceB.hold(source)).isTrue();
        assertThat(instanceB.checkpoint(source)).contains(new FileTailer.Checkpoint("(dev=1,ino=2)", 128));
        instanceB.release(source);
    }

    private BlockingQueue<String> subscribe(String topic) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new StringMessageConverter());
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                new StompHeaders(), new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((String) payload);
            }
        });
        return received;
    }
}
