package com.snapland.gis.area;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AreaVersionRepository extends JpaRepository<AreaVersion, UUID> {

    Optional<AreaVersion> findByAreaIdAndVersion(UUID areaId, int version);

    List<AreaVersion> findByAreaIdOrderByVersionDesc(UUID areaId);

    @Modifying
    @Query(value = "DELETE FROM area_version WHERE area_id = :areaId", nativeQuery = true)
    void deleteAllVersionsByAreaId(@Param("areaId") UUID areaId);
}
