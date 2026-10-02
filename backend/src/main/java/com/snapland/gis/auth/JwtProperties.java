package com.snapland.gis.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record JwtProperties(String jwtSecret, Duration jwtExpiration) {}
