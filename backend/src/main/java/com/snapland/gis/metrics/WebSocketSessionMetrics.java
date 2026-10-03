package com.snapland.gis.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the number of active authenticated STOMP WebSocket sessions.
 *
 * SessionConnectedEvent fires after the server has sent the STOMP CONNECTED
 * frame to the client, meaning the STOMP handshake is complete and the session
 * is fully established. Attempts rejected by JwtChannelInterceptor (bad or
 * missing JWT) result in a STOMP ERROR frame and never reach CONNECTED, so they
 * are not counted.
 *
 * SessionDisconnectEvent fires when a session ends — graceful (client DISCONNECT)
 * or abrupt (network failure / heartbeat timeout). The event may fire more than
 * once for the same session in edge cases. Using a Set makes removal idempotent:
 * the gauge cannot go negative from duplicate events.
 */
@Component
public class WebSocketSessionMetrics {

    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public WebSocketSessionMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("websocket.connections.active", activeSessions, Set::size)
                .description("Active authenticated STOMP WebSocket sessions")
                .register(meterRegistry);
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            activeSessions.add(sessionId);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        activeSessions.remove(event.getSessionId());
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Returns the live set of active session IDs.
     * Exposed for testing: allows verifying idempotent-remove behaviour
     * without constructing Spring WebSocket events.
     */
    public Set<String> getActiveSessions() {
        return activeSessions;
    }
}
