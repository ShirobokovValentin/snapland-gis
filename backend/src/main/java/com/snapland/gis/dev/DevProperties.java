package com.snapland.gis.dev;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "app.dev")
public record DevProperties(UUID userId, UUID workspaceId) {
}
