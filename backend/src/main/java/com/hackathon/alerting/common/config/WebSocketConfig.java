package com.hackathon.alerting.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws} with an in-memory broker for {@link WebSocketTopics}.
 * Server code pushes with {@code SimpMessagingTemplate.convertAndSend(destination, dto)}.
 *
 * <p>The UI only subscribes; there are no client-to-server STOMP messages ({@code /app} is reserved).
 * All origins are allowed because the service has no authentication and the dev UI runs on another port.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(WebSocketTopics.PREFIX);
        registry.setApplicationDestinationPrefixes("/app");
    }
}
