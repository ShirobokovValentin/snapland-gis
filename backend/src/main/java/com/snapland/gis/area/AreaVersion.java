package com.snapland.gis.area;

import com.snapland.gis.user.User;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "area_version",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_area_version",
        columnNames = {"area_id", "version"}
    )
)
public class AreaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "geometry(POLYGON, 4326)", nullable = false)
    private Polygon geometry;

    @Column(name = "area_km2", nullable = false, precision = 20, scale = 6)
    private BigDecimal areaKm2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AreaStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 50)
    private ChangeType changeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AreaVersion() {
    }

    public AreaVersion(Area area, int version, String name, Polygon geometry,
                       BigDecimal areaKm2, AreaStatus status, ChangeType changeType, User createdBy) {
        this.area = area;
        this.version = version;
        this.name = name;
        this.geometry = geometry;
        this.areaKm2 = areaKm2;
        this.status = status;
        this.changeType = changeType;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public Area getArea() {
        return area;
    }

    public int getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public Polygon getGeometry() {
        return geometry;
    }

    public BigDecimal getAreaKm2() {
        return areaKm2;
    }

    public AreaStatus getStatus() {
        return status;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
