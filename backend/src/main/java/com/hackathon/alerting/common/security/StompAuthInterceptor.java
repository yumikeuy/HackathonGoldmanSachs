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

import java.security.Principal;

/**
 * Authenticates the live feed: with login enabled a STOMP {@code CONNECT} to {@code /ws} must carry
 * {@code Authorization: Bearer <access token>} as a native STOMP header (browsers cannot set HTTP headers on a
 * WebSocket handshake). Without a valid token the connection is refused with a STOMP ERROR frame, so nobody can
 * subscribe to alerts anonymously. An administrator may subscribe to every topic; anyone else only to
 * {@code /topic/alerts/{ownTeam}} (team isolation, docs/security.md); other subscriptions are silently dropped.
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
        if (accessor == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            // a forbidden subscription is dropped (no data), not fatal: the connection and other subscriptions stay
            return allowedToSubscribe(accessor) ? message : null;
        }
        if (accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessageDeliveryException("Log in first: send 'Authorization: Bearer <access token>' in CONNECT");
        }
        try {
            Jwt jwt = decoder.decode(header.substring(7).trim());
            accessor.setUser(new StompUser(jwt.getSubject(), jwt.getClaimAsString("role"),
                    jwt.getClaims().get("teamId") instanceof Number n ? n.longValue() : null));
            return message;
        } catch (JwtException e) {
            throw new MessageDeliveryException("Invalid or expired access token");
        }
    }

    /** A non-administrator may follow only their own team's feed: {@code /topic/alerts/{ownTeam}}. */
    private static boolean allowedToSubscribe(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompUser user) || "ADMIN".equals(user.role())) {
            return true;
        }
        String destination = accessor.getDestination();
        return destination != null && user.teamId() != null && destination.equals("/topic/alerts/" + user.teamId());
    }

    /** The STOMP session's user: subject, role and team from the access token. */
    record StompUser(String subject, String role, Long teamId) implements Principal {
        @Override
        public String getName() {
            return subject;
        }
    }
}
