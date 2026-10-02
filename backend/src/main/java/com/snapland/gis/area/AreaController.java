package com.snapland.gis.area;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.AreaVersionResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
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
            @RequestParam String bbox) {
        return areaService.findInViewport(workspaceId, bbox);
    }

    @PutMapping("/{areaId}")
    public AreaResponse updateArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
            @Valid @RequestBody UpdateAreaRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return areaService.updateArea(workspaceId, areaId, request, UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping("/{areaId}")
    public AreaResponse deleteArea(
            @PathVariable UUID workspaceId,
            @PathVariable UUID areaId,
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
