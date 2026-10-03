package com.snapland.gis.area.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateAreaRequest(
        @NotNull
        @Schema(description = "Must match the current Area version. Returns 409 VERSION_CONFLICT if stale.")
        Integer expectedVersion,
        @NotBlank String name,
        @NotNull GeoJsonGeometry geometry
) {
}
