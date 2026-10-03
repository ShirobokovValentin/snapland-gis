package com.snapland.gis.workspace;

import com.snapland.gis.common.exception.WorkspaceAccessDeniedException;
import com.snapland.gis.common.exception.WorkspaceNotFoundException;
import com.snapland.gis.realtime.WorkspaceEventMessage;
import com.snapland.gis.user.User;
import com.snapland.gis.user.UserRepository;
import com.snapland.gis.workspace.dto.WorkspaceResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            UserRepository userRepository,
                            JdbcTemplate jdbcTemplate,
                            ApplicationEventPublisher eventPublisher) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    public List<WorkspaceResponse> findAll() {
        return workspaceRepository.findAllOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public WorkspaceResponse createWorkspace(String name, UUID createdByUserId) {
        User user = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + createdByUserId));
        Workspace workspace = workspaceRepository.save(new Workspace(name, user));
        return toResponse(workspace);
    }

    @Transactional
    public void deleteWorkspace(UUID workspaceId, UUID requestingUserId) {
        Workspace workspace = workspaceRepository.findByIdWithCreator(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        UUID creatorId = workspace.getCreatedBy().getId();
        if (!creatorId.equals(requestingUserId)) {
            throw new WorkspaceAccessDeniedException(workspaceId);
        }

        jdbcTemplate.update(
                "DELETE FROM area_version WHERE area_id IN (SELECT id FROM area WHERE workspace_id = ?::uuid)",
                workspaceId.toString());
        jdbcTemplate.update(
                "DELETE FROM area WHERE workspace_id = ?::uuid",
                workspaceId.toString());
        jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ?::uuid",
                workspaceId.toString());
        jdbcTemplate.update(
                "DELETE FROM workspace WHERE id = ?::uuid",
                workspaceId.toString());

        eventPublisher.publishEvent(
                new WorkspaceEventMessage("WORKSPACE_DELETED", workspaceId.toString(), Instant.now().toString()));
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getCreatedBy().getId(),
                workspace.getCreatedAt());
    }
}
