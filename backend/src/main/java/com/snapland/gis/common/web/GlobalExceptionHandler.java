package com.snapland.gis.common.web;

import com.snapland.gis.common.exception.AreaDeletedException;
import com.snapland.gis.common.exception.AreaNotFoundException;
import com.snapland.gis.common.exception.AreaVersionNotFoundException;
import com.snapland.gis.common.exception.InvalidGeometryException;
import com.snapland.gis.common.exception.VersionConflictException;
import com.snapland.gis.common.exception.WorkspaceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkspaceNotFound(WorkspaceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("WORKSPACE_NOT_FOUND", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(AreaNotFoundException.class)
    public ResponseEntity<ApiError> handleAreaNotFound(AreaNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("AREA_NOT_FOUND", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(AreaDeletedException.class)
    public ResponseEntity<ApiError> handleAreaDeleted(AreaDeletedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("AREA_DELETED", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ApiError> handleVersionConflict(VersionConflictException ex) {
        Map<String, Object> details = Map.of(
                "areaId", ex.getAreaId(),
                "expectedVersion", ex.getExpectedVersion(),
                "currentVersion", ex.getCurrentVersion()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.withDetails("VERSION_CONFLICT", ex.getMessage(), details, traceId()));
    }

    @ExceptionHandler(AreaVersionNotFoundException.class)
    public ResponseEntity<ApiError> handleAreaVersionNotFound(AreaVersionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("AREA_VERSION_NOT_FOUND", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(InvalidGeometryException.class)
    public ResponseEntity<ApiError> handleInvalidGeometry(InvalidGeometryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_GEOMETRY", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .findFirst()
                .orElse("Invalid request.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_REQUEST", message, traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_REQUEST", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_REQUEST", ex.getMessage(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred.", traceId()));
    }

    private String traceId() {
        return UUID.randomUUID().toString();
    }
}
