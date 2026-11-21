package com.hackathon.alerting.common.config;

import com.hackathon.alerting.common.security.StompAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws} with an in-memory broker for {@link WebSocketTopics}.
 * Server code pushes with {@code SimpMessagingTemplate.convertAndSend(destination, dto)}.
 *
 * <p>The UI only subscribes; there are no client-to-server STOMP messages ({@code /app} is reserved).
 * Cross-origin connections are accepted only from {@code alerting.security.allowed-origins} (the UI served by the
 * service itself is same-origin and always allowed), so a foreign web page cannot read the live alert feed through
 * an operator's browser (docs/security.md).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    private final String[] allowedOrigins;
    private final StompAuthInterceptor stompAuth;

    public WebSocketConfig(@Value("${alerting.security.allowed-origins}") String[] allowedOrigins,
                           StompAuthInterceptor stompAuth) {
        this.allowedOrigins = allowedOrigins;
        this.stompAuth = stompAuth;
    }

    /** With login enabled, CONNECT needs an access token ({@link StompAuthInterceptor}). */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuth);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(WebSocketTopics.PREFIX);
        registry.setApplicationDestinationPrefixes("/app");
    }
}
