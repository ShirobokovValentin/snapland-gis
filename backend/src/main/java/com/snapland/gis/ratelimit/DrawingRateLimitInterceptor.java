package com.snapland.gis.ratelimit;

import com.snapland.gis.common.exception.RateLimitExceededException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.UUID;

/**
 * Enforces per-user drawing-action rate limiting on persistent Area mutation
 * endpoints. Only these four operations are rate-limited:
 *
 *   POST   /workspaces/{workspaceId}/areas
 *   PUT    /workspaces/{workspaceId}/areas/{areaId}
 *   DELETE /workspaces/{workspaceId}/areas/{areaId}
 *   POST   /workspaces/{workspaceId}/areas/{areaId}/versions/{version}/restore
 *
 * Read-only (GET) requests and all non-Area endpoints are never rate-limited.
 * Future POST endpoints that do not match the explicit patterns above are also
 * unaffected. Unauthenticated requests are rejected by Spring Security before
 * this interceptor is reached.
 *
 * An authenticated mutation attempt consumes quota regardless of whether
 * business validation later returns 400/404/409/500.
 */
@Component
public class DrawingRateLimitInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<String[]> MUTATION_PATTERNS = List.of(
            new String[]{"POST",   "/workspaces/*/areas"},
            new String[]{"PUT",    "/workspaces/*/areas/*"},
            new String[]{"DELETE", "/workspaces/*/areas/*"},
            new String[]{"DELETE", "/workspaces/*/areas/*/permanent"},
            new String[]{"POST",   "/workspaces/*/areas/*/versions/*/restore"}
    );

    private final DrawingRateLimiter rateLimiter;
    private final Counter rejectedCounter;

    public DrawingRateLimitInterceptor(DrawingRateLimiter rateLimiter, MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.rejectedCounter = Counter.builder("drawing.rate.limit.rejected.total")
                .description("Number of drawing action requests rejected by the rate limiter")
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isMutationRequest(request)) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return true; // No JWT identity present; Spring Security will reject with 401
        }

        UUID userId = UUID.fromString(jwtAuth.getToken().getSubject());
        long retryAfterSeconds = rateLimiter.tryConsume(userId);
        if (retryAfterSeconds > 0) {
            rejectedCounter.increment();
            throw new RateLimitExceededException(retryAfterSeconds);
        }
        return true;
    }

    private boolean isMutationRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        return MUTATION_PATTERNS.stream()
                .anyMatch(p -> p[0].equals(method) && PATH_MATCHER.match(p[1], path));
    }
}
