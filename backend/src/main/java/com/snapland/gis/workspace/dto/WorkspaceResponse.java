package com.snapland.gis.workspace.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, UUID createdById, Instant createdAt) {}
