package com.snapland.gis.area.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AreaResponse(
        UUID id,
        UUID workspaceId,
        String name,
        GeoJsonGeometry geometry,
        BigDecimal areaKm2,
        String status,
        int currentVersion,
        Instant createdAt
) {
}
