package com.hackathon.alerting.common.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Authenticates the live feed: with login enabled a STOMP {@code CONNECT} to {@code /ws} must carry
 * {@code Authorization: Bearer <access token>} as a native STOMP header (browsers cannot set HTTP headers on a
 * WebSocket handshake). Without a valid token the connection is refused with a STOMP ERROR frame, so nobody can
 * subscribe to alerts anonymously. Any role may subscribe.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final AuthSettings settings;
    private final JwtDecoder decoder;

    public StompAuthInterceptor(AuthSettings settings, JwtDecoder decoder) {
        this.settings = settings;
        this.decoder = decoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (!settings.enabled()) {
            return message;
        }
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessageDeliveryException("Log in first: send 'Authorization: Bearer <access token>' in CONNECT");
        }
        try {
            Jwt jwt = decoder.decode(header.substring(7).trim());
            accessor.setUser(() -> jwt.getSubject());
            return message;
        } catch (JwtException e) {
            throw new MessageDeliveryException("Invalid or expired access token");
        }
    }
}
