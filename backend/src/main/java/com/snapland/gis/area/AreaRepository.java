package com.snapland.gis.area;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AreaRepository extends JpaRepository<Area, UUID> {

    @Query(value = """
            SELECT * FROM area
            WHERE workspace_id = :workspaceId
              AND status = 'ACTIVE'
              AND ST_Intersects(geometry, ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326))
            """, nativeQuery = true)
    List<Area> findActiveInViewport(
            @Param("workspaceId") UUID workspaceId,
            @Param("minLon") double minLon,
            @Param("minLat") double minLat,
            @Param("maxLon") double maxLon,
            @Param("maxLat") double maxLat
    );
}
