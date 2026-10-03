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
 * {@code Authorization: Bearer <token>} header. Also extracts the optional
 * {@code workspace-id} header and registers it in {@link WorkspaceSessionRegistry}
 * so that presence tracking can scope the session to the correct workspace.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final WorkspaceSessionRegistry workspaceSessionRegistry;

    public JwtChannelInterceptor(JwtDecoder jwtDecoder, WorkspaceSessionRegistry workspaceSessionRegistry) {
        this.jwtDecoder = jwtDecoder;
        this.workspaceSessionRegistry = workspaceSessionRegistry;
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
        Jwt jwt = jwtDecoder.decode(token);
        accessor.setUser(new JwtAuthenticationToken(jwt, Collections.emptyList()));

        String workspaceId = accessor.getFirstNativeHeader("workspace-id");
        String sessionId = accessor.getSessionId();
        if (workspaceId != null && !workspaceId.isBlank() && sessionId != null) {
            workspaceSessionRegistry.register(sessionId, workspaceId.trim());
        }

        return message;
    }
}
