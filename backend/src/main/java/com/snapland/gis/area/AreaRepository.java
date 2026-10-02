package com.snapland.gis.area;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    @Modifying
    @Query(value = """
            UPDATE area
            SET name = :name,
                geometry = ST_GeomFromText(:geometryWkt, 4326),
                area_km2 = :areaKm2,
                current_version = current_version + 1
            WHERE id = :areaId
              AND workspace_id = :workspaceId
              AND status = 'ACTIVE'
              AND current_version = :expectedVersion
            """, nativeQuery = true)
    int conditionalUpdate(
            @Param("areaId") UUID areaId,
            @Param("workspaceId") UUID workspaceId,
            @Param("name") String name,
            @Param("geometryWkt") String geometryWkt,
            @Param("areaKm2") BigDecimal areaKm2,
            @Param("expectedVersion") int expectedVersion
    );
}
