package com.snapland.gis.realtime;

import com.snapland.gis.common.exception.WorkspaceNotFoundException;
import com.snapland.gis.workspace.WorkspaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workspaces/{workspaceId}")
public class PresenceController {

    private final PresenceService presenceService;
    private final WorkspaceRepository workspaceRepository;

    public PresenceController(PresenceService presenceService, WorkspaceRepository workspaceRepository) {
        this.presenceService = presenceService;
        this.workspaceRepository = workspaceRepository;
    }

    @GetMapping("/presence")
    public List<UserPresenceResponse> getPresence(@PathVariable UUID workspaceId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return presenceService.getActiveUsers(workspaceId.toString());
    }
}
