package com.snapland.gis.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Standard error response body returned for all API error cases")
public record ApiError(
        @Schema(description = "Machine-readable error code", example = "VERSION_CONFLICT")
        String code,
        @Schema(description = "Human-readable error description")
        String message,
        @Schema(description = "Optional additional context; empty object when not applicable")
        Object details,
        @Schema(description = "Correlation ID for matching backend log entries")
        String traceId
) {

    static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, Map.of(), traceId);
    }

    static ApiError withDetails(String code, String message, Map<String, Object> details, String traceId) {
        return new ApiError(code, message, details, traceId);
    }
}
