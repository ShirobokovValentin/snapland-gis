package com.snapland.gis.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Authenticates STOMP CONNECT frames using the JWT carried in the
 * {@code Authorization: Bearer <token>} header. On success the decoded JWT is
 * set as the WebSocket session's {@link java.security.Principal} so that later
 * SUBSCRIBE and SEND frames can inspect the authenticated identity.
 *
 * The HTTP upgrade (/ws/**) remains permitAll because browser WebSocket clients
 * cannot send custom headers during the HTTP handshake; authentication is
 * enforced here at the application-protocol level instead.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public JwtChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new BadCredentialsException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        // JwtDecoder throws JwtException (BadJwtException / JwtValidationException)
        // for invalid signatures and expired tokens. Spring's STOMP infrastructure
        // catches MessagingException-wrapped runtime exceptions from preSend, sends
        // a STOMP ERROR frame, and closes the WebSocket session.
        Jwt jwt = jwtDecoder.decode(token);

        accessor.setUser(new JwtAuthenticationToken(jwt, Collections.emptyList()));
        return message;
    }
}
