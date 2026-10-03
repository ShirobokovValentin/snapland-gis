package com.snapland.gis.area;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.AreaVersionResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.DeletedAreaResponse;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workspaces/{workspaceId}/areas")
public class AreaController {

    private final AreaService areaService;

    public AreaController(AreaService areaService) {
        this.areaService = areaService;
    }

    @Operation(description = "Creates a new Area. Subject to rate limit: 50 mutations/min/user.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "INVALID_GEOMETRY — polygon is self-intersecting, uses unsupported type, or has invalid coordinates")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED — 50 area mutations/min/user. See Retry-After header.")
    @PostMapping
    public ResponseEntity<AreaResponse> createArea(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateAreaRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AreaResponse response = areaService.createArea(workspaceId, request, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<AreaResponse> getAreas(
            @PathVariable UUID workspaceId,
            @Parameter(description = "Bounding box as minLon,minLat,maxLon,maxLat (WGS 84 decimal degrees). Returns all ACTIVE Areas whose geometry intersects this rectangle.", example = "34.75,31.70,34.85,31.80")
            @RequestParam String bbox) {
        return areaService.findInViewport(workspaceId, bbox);
    }

    @GetMapping("/deleted")
    public List<DeletedAreaResponse> getDeletedAreas(@PathVariable UUID workspaceId) {
        return areaService.findDeleted(workspaceId);
    }

    @Operation(description = "Updates an existing Area's name and/or geometry. Subject to rate limit: 50 mutations/min/user. expectedVersion in the request body must match Area.current_version; otherwise 409 VERSION_CONFLICT.")
    @ApiResponse(responseCode = "400", description = "INVALID_GEOMETRY")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale expectedVersion; or AREA_DELETED — Area is already soft-deleted")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED. See Retry-After header.")
    @PutMapping("/{areaId}")
    public AreaResponse updateArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
            @Valid @RequestBody UpdateAreaRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return areaService.updateArea(workspaceId, areaId, request, UUID.fromString(jwt.getSubject()));
    }

    @Operation(description = "Soft-deletes the Area. Area mutation. Subject to rate limit: 50 mutations/min/user.")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — expectedVersion does not match Area.current_version")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED. See Retry-After header.")
    @DeleteMapping("/{areaId}")
    public AreaResponse deleteArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
            @Parameter(description = "Must match Area.current_version. Returns 409 VERSION_CONFLICT if stale.")
            @RequestParam int expectedVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return areaService.deleteArea(workspaceId, areaId, expectedVersion, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{areaId}/versions")
    public List<AreaVersionResponse> getAreaVersions(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId) {
        return areaService.getAreaVersions(workspaceId, areaId);
    }

    @Operation(description = "Physically removes the Area and all its version history. The Area must be in DELETED status (soft-delete first). Subject to rate limit.")
    @ApiResponse(responseCode = "409", description = "AREA_NOT_DELETED — Area is still ACTIVE; or VERSION_CONFLICT — stale expectedVersion")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED. See Retry-After header.")
    @DeleteMapping("/{areaId}/permanent")
    public AreaResponse permanentDeleteArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
            @Parameter(description = "Must match Area.current_version. Returns 409 VERSION_CONFLICT if stale.")
            @RequestParam int expectedVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return areaService.permanentDeleteArea(workspaceId, areaId, expectedVersion, UUID.fromString(jwt.getSubject()));
    }

    @Operation(description = "Restores a historical AreaVersion as the new current state. Area mutation. Subject to rate limit. expectedVersion in the request body must match Area.current_version.")
    @ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale expectedVersion")
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED. See Retry-After header.")
    @PostMapping("/{areaId}/versions/{version}/restore")
    public AreaResponse restoreArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
            @PathVariable int version,
            @Valid @RequestBody RestoreAreaRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return areaService.restoreArea(workspaceId, areaId, version, request, UUID.fromString(jwt.getSubject()));
    }
}
