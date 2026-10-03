package com.snapland.gis.area;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.AreaVersionResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.DeletedAreaResponse;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import com.snapland.gis.realtime.AreaEventMessage;
import com.snapland.gis.common.exception.AreaDeletedException;
import com.snapland.gis.common.exception.AreaNotFoundException;
import com.snapland.gis.common.exception.AreaNotDeletedException;
import com.snapland.gis.common.exception.AreaVersionNotFoundException;
import com.snapland.gis.common.exception.InvalidGeometryException;
import com.snapland.gis.common.exception.VersionConflictException;
import com.snapland.gis.common.exception.WorkspaceNotFoundException;
import com.snapland.gis.user.User;
import com.snapland.gis.user.UserRepository;
import com.snapland.gis.workspace.Workspace;
import com.snapland.gis.workspace.WorkspaceRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AreaService {

    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final AreaRepository areaRepository;
    private final AreaVersionRepository areaVersionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public AreaService(AreaRepository areaRepository,
                       AreaVersionRepository areaVersionRepository,
                       WorkspaceRepository workspaceRepository,
                       UserRepository userRepository,
                       JdbcTemplate jdbcTemplate,
                       ApplicationEventPublisher eventPublisher) {
        this.areaRepository = areaRepository;
        this.areaVersionRepository = areaVersionRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AreaResponse createArea(UUID workspaceId, CreateAreaRequest request, UUID createdByUserId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        Polygon polygon = validateAndBuildPolygon(request.geometry());
        BigDecimal areaKm2 = calculateAreaKm2(polygon);

        User createdBy = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current user not found: " + createdByUserId));

        Area area = areaRepository.save(new Area(
                workspace, request.name(), polygon, areaKm2,
                AreaStatus.ACTIVE, 1, createdBy));

        areaVersionRepository.save(new AreaVersion(
                area, 1, request.name(), polygon, areaKm2,
                AreaStatus.ACTIVE, ChangeType.CREATED, createdBy));

        AreaResponse response = toResponse(area);
        eventPublisher.publishEvent(
                new AreaEventMessage("AREA_CREATED", workspaceId.toString(), Instant.now().toString(), response));
        return response;
    }

    @Transactional
    public AreaResponse updateArea(UUID workspaceId, UUID areaId, UpdateAreaRequest request, UUID updatedByUserId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        Polygon polygon = validateAndBuildPolygon(request.geometry());
        BigDecimal areaKm2 = calculateAreaKm2(polygon);

        int affected = areaRepository.conditionalUpdate(
                areaId, workspaceId, request.name(), polygon.toText(), areaKm2, request.expectedVersion());

        if (affected == 0) {
            Area area = areaRepository.findById(areaId).orElseThrow(() -> new AreaNotFoundException(areaId));
            if (!workspaceId.equals(area.getWorkspace().getId())) {
                throw new AreaNotFoundException(areaId);
            }
            if (area.getStatus() == AreaStatus.DELETED) {
                throw new AreaDeletedException(areaId);
            }
            throw new VersionConflictException(areaId, request.expectedVersion(), area.getCurrentVersion());
        }

        User updatedBy = userRepository.findById(updatedByUserId)
                .orElseThrow(() -> new IllegalStateException("Current user not found: " + updatedByUserId));

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaNotFoundException(areaId));

        int newVersion = area.getCurrentVersion();
        areaVersionRepository.save(new AreaVersion(
                area, newVersion, request.name(), polygon, areaKm2,
                AreaStatus.ACTIVE, ChangeType.UPDATED, updatedBy));

        AreaResponse response = toResponse(area);
        eventPublisher.publishEvent(
                new AreaEventMessage("AREA_UPDATED", workspaceId.toString(), Instant.now().toString(), response));
        return response;
    }

    @Transactional
    public AreaResponse deleteArea(UUID workspaceId, UUID areaId, int expectedVersion, UUID deletedByUserId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        int affected = areaRepository.conditionalDelete(areaId, workspaceId, expectedVersion);

        if (affected == 0) {
            Area area = areaRepository.findById(areaId)
                    .orElseThrow(() -> new AreaNotFoundException(areaId));
            if (!workspaceId.equals(area.getWorkspace().getId())) {
                throw new AreaNotFoundException(areaId);
            }
            if (area.getCurrentVersion() != expectedVersion) {
                throw new VersionConflictException(areaId, expectedVersion, area.getCurrentVersion());
            }
            throw new AreaDeletedException(areaId);
        }

        User deletedBy = userRepository.findById(deletedByUserId)
                .orElseThrow(() -> new IllegalStateException("Current user not found: " + deletedByUserId));

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaNotFoundException(areaId));

        int newVersion = area.getCurrentVersion();
        areaVersionRepository.save(new AreaVersion(
                area, newVersion, area.getName(), area.getGeometry(), area.getAreaKm2(),
                AreaStatus.DELETED, ChangeType.DELETED, deletedBy));

        AreaResponse response = toResponse(area);
        eventPublisher.publishEvent(
                new AreaEventMessage("AREA_DELETED", workspaceId.toString(), Instant.now().toString(), response));
        return response;
    }

    @Transactional
    public AreaResponse restoreArea(UUID workspaceId, UUID areaId, int targetVersion,
                                    RestoreAreaRequest request, UUID restoredByUserId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        AreaVersion targetAreaVersion = areaVersionRepository.findByAreaIdAndVersion(areaId, targetVersion)
                .orElseThrow(() -> new AreaVersionNotFoundException(areaId, targetVersion));

        int affected = areaRepository.conditionalRestore(
                areaId, workspaceId,
                targetAreaVersion.getName(),
                targetAreaVersion.getGeometry().toText(),
                targetAreaVersion.getAreaKm2(),
                request.expectedVersion());

        if (affected == 0) {
            Area area = areaRepository.findById(areaId)
                    .orElseThrow(() -> new AreaNotFoundException(areaId));
            if (!workspaceId.equals(area.getWorkspace().getId())) {
                throw new AreaNotFoundException(areaId);
            }
            throw new VersionConflictException(areaId, request.expectedVersion(), area.getCurrentVersion());
        }

        User restoredBy = userRepository.findById(restoredByUserId)
                .orElseThrow(() -> new IllegalStateException("Current user not found: " + restoredByUserId));

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaNotFoundException(areaId));

        int newVersion = area.getCurrentVersion();
        areaVersionRepository.save(new AreaVersion(
                area, newVersion, targetAreaVersion.getName(),
                targetAreaVersion.getGeometry(), targetAreaVersion.getAreaKm2(),
                AreaStatus.ACTIVE, ChangeType.RESTORED, restoredBy));

        AreaResponse response = toResponse(area);
        eventPublisher.publishEvent(
                new AreaEventMessage("AREA_RESTORED", workspaceId.toString(), Instant.now().toString(), response));
        return response;
    }

    public List<AreaVersionResponse> getAreaVersions(UUID workspaceId, UUID areaId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaNotFoundException(areaId));
        if (!workspaceId.equals(area.getWorkspace().getId())) {
            throw new AreaNotFoundException(areaId);
        }

        return areaVersionRepository.findByAreaIdOrderByVersionDesc(areaId)
                .stream()
                .map(this::toVersionResponse)
                .toList();
    }

    public List<DeletedAreaResponse> findDeleted(UUID workspaceId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        return jdbcTemplate.query(
                """
                SELECT a.id, a.workspace_id, a.name, a.current_version, av.created_at AS deleted_at
                FROM area a
                JOIN area_version av ON av.area_id = a.id AND av.version = a.current_version
                WHERE a.workspace_id = ?::uuid
                  AND a.status = 'DELETED'
                ORDER BY av.created_at DESC
                """,
                (rs, rowNum) -> new DeletedAreaResponse(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        rs.getString("name"),
                        rs.getInt("current_version"),
                        rs.getTimestamp("deleted_at").toInstant()
                ),
                workspaceId.toString()
        );
    }

    @Transactional
    public AreaResponse permanentDeleteArea(UUID workspaceId, UUID areaId, int expectedVersion,
                                            UUID deletedByUserId) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        Area area = areaRepository.findById(areaId)
                .orElseThrow(() -> new AreaNotFoundException(areaId));
        if (!workspaceId.equals(area.getWorkspace().getId())) {
            throw new AreaNotFoundException(areaId);
        }
        if (area.getStatus() == AreaStatus.ACTIVE) {
            throw new AreaNotDeletedException(areaId);
        }

        // Capture response before deletion (area row is about to be removed)
        AreaResponse response = toResponse(area);

        // Delete all AreaVersion rows first (FK requires child rows removed before parent)
        areaVersionRepository.deleteAllVersionsByAreaId(areaId);

        // Atomic OCC delete: verifies status=DELETED and current_version=expectedVersion
        int affected = areaRepository.conditionalPermanentDelete(areaId, workspaceId, expectedVersion);

        if (affected == 0) {
            // Persistence context was cleared by conditionalPermanentDelete (clearAutomatically=true)
            Area current = areaRepository.findById(areaId)
                    .orElseThrow(() -> new AreaNotFoundException(areaId));
            throw new VersionConflictException(areaId, expectedVersion, current.getCurrentVersion());
        }

        eventPublisher.publishEvent(new AreaEventMessage(
                "AREA_PERMANENTLY_DELETED", workspaceId.toString(), Instant.now().toString(), response));
        return response;
    }

    public List<AreaResponse> findInViewport(UUID workspaceId, String bboxParam) {
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        double[] bbox = parseBbox(bboxParam);
        return areaRepository.findActiveInViewport(workspaceId, bbox[0], bbox[1], bbox[2], bbox[3])
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private Polygon validateAndBuildPolygon(GeoJsonGeometry geoJson) {
        if (!"Polygon".equals(geoJson.type())) {
            throw new InvalidGeometryException("Only Polygon geometry type is supported.");
        }
        if (geoJson.coordinates() == null || geoJson.coordinates().isEmpty()) {
            throw new InvalidGeometryException("Geometry coordinates are required.");
        }
        if (geoJson.coordinates().size() > 1) {
            throw new InvalidGeometryException(
                    "Polygon with interior rings (holes) is not supported.");
        }
        Polygon polygon = buildPolygon(geoJson.coordinates().get(0));
        validatePolygon(polygon);
        return polygon;
    }

    private Polygon buildPolygon(List<List<Double>> ring) {
        if (ring.size() < 4) {
            throw new InvalidGeometryException(
                    "Polygon ring must have at least 4 coordinates including the closing point.");
        }

        Coordinate[] coords = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            List<Double> point = ring.get(i);
            if (point.size() < 2) {
                throw new InvalidGeometryException(
                        "Each coordinate must contain at least longitude and latitude.");
            }
            double lon = point.get(0);
            double lat = point.get(1);
            if (lon < -180 || lon > 180) {
                throw new InvalidGeometryException(
                        "Longitude must be between -180 and 180, got: " + lon);
            }
            if (lat < -90 || lat > 90) {
                throw new InvalidGeometryException(
                        "Latitude must be between -90 and 90, got: " + lat);
            }
            coords[i] = new Coordinate(lon, lat);
        }

        if (!coords[0].equals2D(coords[coords.length - 1])) {
            throw new InvalidGeometryException(
                    "Polygon ring is not closed: first and last coordinate positions must be equal.");
        }

        try {
            return GEOMETRY_FACTORY.createPolygon(coords);
        } catch (IllegalArgumentException e) {
            throw new InvalidGeometryException("Invalid polygon ring: " + e.getMessage());
        }
    }

    private void validatePolygon(Polygon polygon) {
        if (!polygon.isValid()) {
            throw new InvalidGeometryException(
                    "Polygon geometry is not valid (e.g., self-intersecting).");
        }
    }

    private BigDecimal calculateAreaKm2(Polygon polygon) {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(ST_Area(ST_GeomFromText(?, 4326)::geography) / 1000000.0 AS NUMERIC(20, 6))",
                BigDecimal.class,
                polygon.toText());
    }

    private double[] parseBbox(String bboxParam) {
        if (bboxParam == null || bboxParam.isBlank()) {
            throw new IllegalArgumentException("bbox parameter is required.");
        }
        String[] parts = bboxParam.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "bbox must have exactly 4 comma-separated values: minLon,minLat,maxLon,maxLat");
        }
        try {
            double minLon = Double.parseDouble(parts[0].trim());
            double minLat = Double.parseDouble(parts[1].trim());
            double maxLon = Double.parseDouble(parts[2].trim());
            double maxLat = Double.parseDouble(parts[3].trim());
            if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
                throw new IllegalArgumentException("bbox coordinates are outside valid WGS84 ranges.");
            }
            return new double[]{minLon, minLat, maxLon, maxLat};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bbox values must be valid decimal numbers.");
        }
    }

    private AreaResponse toResponse(Area area) {
        return new AreaResponse(
                area.getId(),
                area.getWorkspace().getId(),
                area.getName(),
                toGeoJson(area.getGeometry()),
                area.getAreaKm2(),
                area.getStatus().name(),
                area.getCurrentVersion(),
                area.getCreatedAt());
    }

    private GeoJsonGeometry toGeoJson(Polygon polygon) {
        Coordinate[] coords = polygon.getExteriorRing().getCoordinates();
        List<List<Double>> ring = Arrays.stream(coords)
                .map(c -> List.of(c.x, c.y))
                .toList();
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private AreaVersionResponse toVersionResponse(AreaVersion av) {
        return new AreaVersionResponse(
                av.getId(),
                av.getVersion(),
                av.getName(),
                toGeoJson(av.getGeometry()),
                av.getAreaKm2(),
                av.getStatus().name(),
                av.getChangeType().name(),
                av.getCreatedBy().getId(),
                av.getCreatedAt());
    }
}
