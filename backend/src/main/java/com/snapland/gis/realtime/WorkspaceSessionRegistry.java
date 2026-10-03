package com.snapland.gis.realtime;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the STOMP CONNECT interceptor and presence tracking.
 * Stores the workspace-id header from each CONNECT frame by session ID.
 */
@Component
public class WorkspaceSessionRegistry {

    private final ConcurrentHashMap<String, String> sessionToWorkspace = new ConcurrentHashMap<>();

    public void register(String sessionId, String workspaceId) {
        sessionToWorkspace.put(sessionId, workspaceId);
    }

    public String getWorkspace(String sessionId) {
        return sessionToWorkspace.get(sessionId);
    }

    public void remove(String sessionId) {
        sessionToWorkspace.remove(sessionId);
    }
}
