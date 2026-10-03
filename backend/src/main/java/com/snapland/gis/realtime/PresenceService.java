package com.snapland.gis.realtime;

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
 * An authenticated user is "active" while they have at least one established STOMP session
 * in a given workspace. Multiple browser tabs for the same user and workspace collapse to
 * a single presence entry.
 *
 * PRESENCE_JOINED is broadcast when the first session for a user in a workspace connects.
 * PRESENCE_LEFT is broadcast when the last session for a user in a workspace disconnects.
 *
 * The workspace is determined by the {@code workspace-id} STOMP CONNECT header, which is
 * registered in {@link WorkspaceSessionRegistry} by {@link JwtChannelInterceptor}.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    // workspaceId -> userId -> Set of active sessionIds
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> workspaceSessions =
            new ConcurrentHashMap<>();
    // sessionId -> userId
    private final ConcurrentHashMap<String, String> sessionToUser = new ConcurrentHashMap<>();
    // sessionId -> workspaceId
    private final ConcurrentHashMap<String, String> sessionToWorkspace = new ConcurrentHashMap<>();
    // userId -> display name
    private final ConcurrentHashMap<String, String> userNames = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final WorkspaceSessionRegistry workspaceSessionRegistry;

    public PresenceService(SimpMessagingTemplate messagingTemplate,
                           UserRepository userRepository,
                           WorkspaceSessionRegistry workspaceSessionRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.workspaceSessionRegistry = workspaceSessionRegistry;
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        String workspaceId = workspaceSessionRegistry.getWorkspace(sessionId);
        if (workspaceId == null) return;

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
        sessionToWorkspace.put(sessionId, workspaceId);
        userNames.putIfAbsent(userId, name);

        ConcurrentHashMap<String, Set<String>> userSessionsForWorkspace =
                workspaceSessions.computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>());

        boolean[] isFirst = {false};
        userSessionsForWorkspace.compute(userId, (k, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
                isFirst[0] = true;
            }
            sessions.add(sessionId);
            return sessions;
        });

        if (isFirst[0]) {
            broadcast("PRESENCE_JOINED", userId, name, workspaceId);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String userId = sessionToUser.remove(sessionId);
        String workspaceId = sessionToWorkspace.remove(sessionId);
        workspaceSessionRegistry.remove(sessionId);

        if (userId == null || workspaceId == null) {
            return;
        }

        ConcurrentHashMap<String, Set<String>> userSessionsForWorkspace =
                workspaceSessions.get(workspaceId);
        if (userSessionsForWorkspace == null) return;

        boolean[] wasLast = {false};
        userSessionsForWorkspace.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                wasLast[0] = true;
                return null;
            }
            return sessions;
        });

        if (wasLast[0]) {
            String name = userNames.remove(userId);
            broadcast("PRESENCE_LEFT", userId, name != null ? name : userId, workspaceId);
        }
    }

    public List<UserPresenceResponse> getActiveUsers(String workspaceId) {
        ConcurrentHashMap<String, Set<String>> userSessionsForWorkspace =
                workspaceSessions.get(workspaceId);
        if (userSessionsForWorkspace == null) return List.of();
        return userSessionsForWorkspace.keySet().stream()
                .map(userId -> new UserPresenceResponse(userId, userNames.getOrDefault(userId, userId)))
                .toList();
    }

    private void broadcast(String type, String userId, String name, String workspaceId) {
        PresenceEventMessage event = new PresenceEventMessage(type, new UserPresenceResponse(userId, name));
        try {
            messagingTemplate.convertAndSend(
                    "/topic/workspace/" + workspaceId + "/presence", event);
        } catch (Exception e) {
            log.error("Failed to deliver presence event {} for user {} in workspace {}", type, userId, workspaceId, e);
        }
    }
}
