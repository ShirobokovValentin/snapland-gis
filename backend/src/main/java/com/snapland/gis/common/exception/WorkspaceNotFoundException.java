package com.snapland.gis.common.exception;

import java.util.UUID;

public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException(UUID workspaceId) {
        super("Workspace not found: " + workspaceId);
    }
}
