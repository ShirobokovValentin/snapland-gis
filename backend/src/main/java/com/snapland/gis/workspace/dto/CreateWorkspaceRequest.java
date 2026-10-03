package com.snapland.gis.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank(message = "Name is required.")
        @Size(max = 255, message = "Name must not exceed 255 characters.")
        String name
) {}
