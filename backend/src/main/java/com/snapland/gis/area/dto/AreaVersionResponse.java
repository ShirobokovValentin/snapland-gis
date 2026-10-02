package com.snapland.gis.area.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AreaVersionResponse(
        UUID id,
        int version,
        String name,
        GeoJsonGeometry geometry,
        BigDecimal areaKm2,
        String status,
        String changeType,
        UUID createdBy,
        Instant createdAt
) {
}
