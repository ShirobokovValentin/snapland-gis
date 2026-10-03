package com.snapland.gis.realtime;

import com.snapland.gis.common.exception.WorkspaceNotFoundException;
import com.snapland.gis.config.PrototypeProperties;
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
    private final PrototypeProperties prototypeProperties;

    public PresenceController(PresenceService presenceService, PrototypeProperties prototypeProperties) {
        this.presenceService = presenceService;
        this.prototypeProperties = prototypeProperties;
    }

    @GetMapping("/presence")
    public List<UserPresenceResponse> getPresence(@PathVariable UUID workspaceId) {
        if (!prototypeProperties.workspaceId().equals(workspaceId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return presenceService.getActiveUsers();
    }
}
