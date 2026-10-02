package com.snapland.gis.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Email is required.")
    @Email(message = "Email must be a valid address.")
    String email
) {}
