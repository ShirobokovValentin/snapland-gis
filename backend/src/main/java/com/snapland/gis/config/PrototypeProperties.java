package com.snapland.gis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "app.prototype")
public record PrototypeProperties(UUID workspaceId) {}
