package com.snapland.gis.realtime;

import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks authenticated STOMP sessions and derives per-workspace active-user presence.
 *
 * An authenticated user is "active" while they have at least one established STOMP session.
 * Multiple browser tabs/devices for the same user collapse to a single presence entry.
 *
 * PRESENCE_JOINED is broadcast when the first session for a user connects.
 * PRESENCE_LEFT is broadcast when the last session for a user disconnects.
 *
 * State is ephemeral: cleared on application restart. Persistence is not required
 * because presence is derived from live session state, not historical data.
 *
 * This prototype supports a single workspace (PrototypeProperties.workspaceId()).
 * All sessions are implicitly scoped to that workspace.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    // userId (String) -> Set of active sessionIds
    private final ConcurrentHashMap<String, Set<String>> userSessions = new ConcurrentHashMap<>();
    // sessionId -> userId (reverse lookup for efficient disconnect handling)
    private final ConcurrentHashMap<String, String> sessionToUser = new ConcurrentHashMap<>();
    // userId -> display name (populated at first connect, removed at last disconnect)
    private final ConcurrentHashMap<String, String> userNames = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final PrototypeProperties prototypeProperties;
    private final UserRepository userRepository;

    public PresenceService(SimpMessagingTemplate messagingTemplate,
                           PrototypeProperties prototypeProperties,
                           UserRepository userRepository) {
        this.messagingTemplate = messagingTemplate;
        this.prototypeProperties = prototypeProperties;
        this.userRepository = userRepository;
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        String userId = principal.getName();
        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            log.warn("Presence: ignoring session with non-UUID principal '{}'", userId);
            return;
        }

        String name = userRepository.findById(userUuid)
                .map(u -> u.getName())
                .orElseGet(() -> {
                    log.warn("Presence: user {} not found in database, using id as fallback name", userId);
                    return userId;
                });

        sessionToUser.put(sessionId, userId);
        userNames.putIfAbsent(userId, name);

        boolean[] isFirst = {false};
        userSessions.compute(userId, (k, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
                isFirst[0] = true;
            }
            sessions.add(sessionId);
            return sessions;
        });

        if (isFirst[0]) {
            broadcast("PRESENCE_JOINED", userId, name);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String userId = sessionToUser.remove(sessionId);
        if (userId == null) {
            // Unknown session: rejected connect (never entered presence) or duplicate disconnect.
            return;
        }

        boolean[] wasLast = {false};
        userSessions.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                wasLast[0] = true;
                return null; // atomically removes the entry
            }
            return sessions;
        });

        if (wasLast[0]) {
            String name = userNames.remove(userId);
            broadcast("PRESENCE_LEFT", userId, name != null ? name : userId);
        }
    }

    public List<UserPresenceResponse> getActiveUsers() {
        return userSessions.keySet().stream()
                .map(userId -> new UserPresenceResponse(userId, userNames.getOrDefault(userId, userId)))
                .toList();
    }

    private void broadcast(String type, String userId, String name) {
        String workspaceId = prototypeProperties.workspaceId().toString();
        PresenceEventMessage event = new PresenceEventMessage(type, new UserPresenceResponse(userId, name));
        try {
            messagingTemplate.convertAndSend(
                    "/topic/workspace/" + workspaceId + "/presence", event);
        } catch (Exception e) {
            log.error("Failed to deliver presence event {} for user {}", type, userId, e);
        }
    }
}
