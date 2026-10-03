package com.snapland.gis.area.dto;

import java.time.Instant;
import java.util.UUID;

public record DeletedAreaResponse(
        UUID id,
        UUID workspaceId,
        String name,
        int currentVersion,
        Instant deletedAt
) {
}
