package com.snapland.gis.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
}
