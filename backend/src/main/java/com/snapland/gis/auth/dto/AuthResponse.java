package com.snapland.gis.auth.dto;

import java.util.UUID;

public record AuthResponse(String token, UserInfo user) {
    public record UserInfo(UUID id, String name, String email) {}
}
