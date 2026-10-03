package com.snapland.gis.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rate-limit.drawing-actions")
public record DrawingRateLimitProperties(int maxActions, Duration window) {}
