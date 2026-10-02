package com.snapland.gis.area.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateAreaRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String name,
        @NotNull GeoJsonGeometry geometry
) {
}
