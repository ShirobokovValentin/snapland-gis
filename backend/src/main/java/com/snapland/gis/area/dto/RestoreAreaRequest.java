package com.snapland.gis.area.dto;

import jakarta.validation.constraints.NotNull;

public record RestoreAreaRequest(
        @NotNull Integer expectedVersion
) {
}
