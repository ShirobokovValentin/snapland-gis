package com.snapland.gis.area;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workspaces/{workspaceId}/areas")
public class AreaController {

    private final AreaService areaService;

    // TODO: replace with authenticated user identity when authentication is implemented
    @Value("${app.dev.user-id:#{null}}")
    private UUID devUserId;

    public AreaController(AreaService areaService) {
        this.areaService = areaService;
    }

    @PostMapping
    public ResponseEntity<AreaResponse> createArea(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateAreaRequest request) {
        AreaResponse response = areaService.createArea(workspaceId, request, devUserId);
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
            @Valid @RequestBody UpdateAreaRequest request) {
        return areaService.updateArea(workspaceId, areaId, request, devUserId);
    }
}
