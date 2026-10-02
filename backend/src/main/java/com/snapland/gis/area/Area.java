package com.snapland.gis.area;

import com.snapland.gis.user.User;
import com.snapland.gis.workspace.Workspace;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "area")
public class Area {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "geometry(POLYGON, 4326)", nullable = false)
    private Polygon geometry;

    @Column(name = "area_km2", nullable = false, precision = 20, scale = 6)
    private BigDecimal areaKm2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AreaStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Area() {
    }

    public Area(Workspace workspace, String name, Polygon geometry, BigDecimal areaKm2,
                AreaStatus status, int currentVersion, User createdBy) {
        this.workspace = workspace;
        this.name = name;
        this.geometry = geometry;
        this.areaKm2 = areaKm2;
        this.status = status;
        this.currentVersion = currentVersion;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Polygon getGeometry() {
        return geometry;
    }

    public void setGeometry(Polygon geometry) {
        this.geometry = geometry;
    }

    public BigDecimal getAreaKm2() {
        return areaKm2;
    }

    public void setAreaKm2(BigDecimal areaKm2) {
        this.areaKm2 = areaKm2;
    }

    public AreaStatus getStatus() {
        return status;
    }

    public void setStatus(AreaStatus status) {
        this.status = status;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
