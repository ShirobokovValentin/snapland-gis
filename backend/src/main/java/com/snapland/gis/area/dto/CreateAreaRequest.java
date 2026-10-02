package com.snapland.gis.area.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAreaRequest(
        @NotBlank String name,
        @NotNull GeoJsonGeometry geometry
) {
}
