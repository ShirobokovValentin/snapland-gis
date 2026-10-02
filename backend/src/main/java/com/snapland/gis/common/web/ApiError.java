package com.snapland.gis.common.web;

import java.util.Map;

public record ApiError(String code, String message, Object details, String traceId) {

    static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, Map.of(), traceId);
    }
}
