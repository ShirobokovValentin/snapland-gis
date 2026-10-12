package com.snapland.gis;

import com.snapland.gis.area.*;
import com.snapland.gis.user.User;
import com.snapland.gis.user.UserRepository;
import com.snapland.gis.workspace.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SchemaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private AreaRepository areaRepository;
    @Autowired
    private AreaVersionRepository areaVersionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private Polygon validPolygon() {
        Coordinate[] coords = {
            new Coordinate(0, 0),
            new Coordinate(1, 0),
            new Coordinate(1, 1),
            new Coordinate(0, 1),
            new Coordinate(0, 0)
        };
        return geometryFactory.createPolygon(coords);
    }

    @Test
    void flywayMigrationsApplied() {
        assertThat(userRepository.count()).isNotNegative();
        assertThat(workspaceRepository.count()).isNotNegative();
        assertThat(workspaceMemberRepository.count()).isNotNegative();
        assertThat(areaRepository.count()).isNotNegative();
        assertThat(areaVersionRepository.count()).isNotNegative();
    }

    @Test
    void postgisExtensionEnabled() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'postgis'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void gistIndexExistsOnAreaGeometry() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'area' AND indexname = 'idx_area_active_geometry'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void basicEntityCrudRoundTrip() {
        User user = userRepository.save(new User("Alice", "alice@example.com"));
        assertThat(user.getId()).isNotNull();

        Workspace workspace = workspaceRepository.save(new Workspace("Test Workspace", user));
        assertThat(workspace.getId()).isNotNull();

        WorkspaceMember member = workspaceMemberRepository.save(
                new WorkspaceMember(workspace, user, WorkspaceMemberRole.OWNER));
        assertThat(member.getId()).isNotNull();

        Area area = areaRepository.save(new Area(
                workspace, "Test Area", validPolygon(),
                new BigDecimal("1.000000"), AreaStatus.ACTIVE, 1, user));
        assertThat(area.getId()).isNotNull();

        AreaVersion version = areaVersionRepository.save(new AreaVersion(
                area, 1, "Test Area", validPolygon(),
                new BigDecimal("1.000000"), AreaStatus.ACTIVE, ChangeType.CREATED, user));
        assertThat(version.getId()).isNotNull();
    }

    @Test
    void geometryPersistedWithCorrectSrid() {
        User user = userRepository.save(new User("Bob", "bob@example.com"));
        Workspace workspace = workspaceRepository.save(new Workspace("Bob WS", user));
        Area area = areaRepository.save(new Area(
                workspace, "Bob Area", validPolygon(),
                new BigDecimal("1.000000"), AreaStatus.ACTIVE, 1, user));

        Integer srid = jdbcTemplate.queryForObject(
                "SELECT ST_SRID(geometry) FROM area WHERE id = ?",
                Integer.class, area.getId());
        assertThat(srid).isEqualTo(4326);
    }

    @Test
    void uniqueEmailConstraintEnforced() {
        userRepository.save(new User("User1", "unique@example.com"));
        assertThatThrownBy(() ->
                userRepository.saveAndFlush(new User("User2", "unique@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueWorkspaceMemberConstraintEnforced() {
        User user = userRepository.save(new User("Dave", "dave@example.com"));
        Workspace workspace = workspaceRepository.save(new Workspace("Dave WS", user));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, user, WorkspaceMemberRole.OWNER));
        assertThatThrownBy(() ->
                workspaceMemberRepository.saveAndFlush(
                        new WorkspaceMember(workspace, user, WorkspaceMemberRole.EDITOR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueAreaVersionConstraintEnforced() {
        User user = userRepository.save(new User("Eve", "eve@example.com"));
        Workspace workspace = workspaceRepository.save(new Workspace("Eve WS", user));
        Area area = areaRepository.save(new Area(
                workspace, "Eve Area", validPolygon(),
                new BigDecimal("1.000000"), AreaStatus.ACTIVE, 1, user));
        areaVersionRepository.save(new AreaVersion(
                area, 1, "Eve Area", validPolygon(),
                new BigDecimal("1.000000"), AreaStatus.ACTIVE, ChangeType.CREATED, user));
        assertThatThrownBy(() ->
                areaVersionRepository.saveAndFlush(new AreaVersion(
                        area, 1, "Eve Area v2", validPolygon(),
                        new BigDecimal("1.000000"), AreaStatus.ACTIVE, ChangeType.UPDATED, user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidGeometryRejectedByCheckConstraint() {
        User user = userRepository.save(new User("Frank", "frank@example.com"));
        Workspace workspace = workspaceRepository.save(new Workspace("Frank WS", user));
        // Bowtie polygon: self-intersects at (1,1) — ST_IsValid returns false
        Coordinate[] coords = {
            new Coordinate(0, 0),
            new Coordinate(2, 2),
            new Coordinate(2, 0),
            new Coordinate(0, 2),
            new Coordinate(0, 0)
        };
        Polygon invalid = geometryFactory.createPolygon(coords);
        assertThatThrownBy(() ->
                areaRepository.saveAndFlush(new Area(
                        workspace, "Bad Area", invalid,
                        new BigDecimal("1.000000"), AreaStatus.ACTIVE, 1, user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
