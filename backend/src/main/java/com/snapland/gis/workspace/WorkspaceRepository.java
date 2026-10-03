package com.snapland.gis.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    @Query("SELECT w FROM Workspace w JOIN FETCH w.createdBy ORDER BY w.createdAt DESC")
    List<Workspace> findAllOrderByCreatedAtDesc();

    @Query("SELECT w FROM Workspace w JOIN FETCH w.createdBy WHERE w.id = :id")
    Optional<Workspace> findByIdWithCreator(@Param("id") UUID id);
}
