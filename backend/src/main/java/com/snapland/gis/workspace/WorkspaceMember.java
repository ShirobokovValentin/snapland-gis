package com.snapland.gis.workspace;

import com.snapland.gis.user.User;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "workspace_member",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_workspace_member",
        columnNames = {"workspace_id", "user_id"}
    )
)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkspaceMemberRole role;

    protected WorkspaceMember() {
    }

    public WorkspaceMember(Workspace workspace, User user, WorkspaceMemberRole role) {
        this.workspace = workspace;
        this.user = user;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public User getUser() {
        return user;
    }

    public WorkspaceMemberRole getRole() {
        return role;
    }

    public void setRole(WorkspaceMemberRole role) {
        this.role = role;
    }
}
