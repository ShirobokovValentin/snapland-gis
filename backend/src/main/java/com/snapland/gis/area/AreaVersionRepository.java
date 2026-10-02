package com.snapland.gis.area;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AreaVersionRepository extends JpaRepository<AreaVersion, UUID> {

    Optional<AreaVersion> findByAreaIdAndVersion(UUID areaId, int version);

    List<AreaVersion> findByAreaIdOrderByVersionDesc(UUID areaId);
}
