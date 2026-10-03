package com.snapland.gis.common.exception;

import java.util.UUID;

public class WorkspaceAccessDeniedException extends RuntimeException {
    public WorkspaceAccessDeniedException(UUID workspaceId) {
        super("Access denied to workspace: " + workspaceId);
    }
}
