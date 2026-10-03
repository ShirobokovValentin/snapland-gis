package com.snapland.gis;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.AreaVersionResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that AreaVersion provides a complete and correct audit trail for every
 * Area mutation: each committed version records the exact authenticated actor,
 * the change type, the resulting version number, and a non-null timestamp.
 *
 * Two distinct users (User A = seeded dev user, User B = per-test registered user)
 * are used so the assertions prove that created_by reflects the actual caller rather
 * than the original Area creator or seed data.
 *
 * TC-AUDIT-001  CREATE records actor, changeType=CREATED, version=1, createdAt
 * TC-AUDIT-002  UPDATE records actor distinct from creator, changeType=UPDATED, version
 * TC-AUDIT-003  DELETE records actor, changeType=DELETED, status=DELETED
 * TC-AUDIT-004  RESTORE records actor distinct from creator, changeType=RESTORED, version
 * TC-AUDIT-005  Failed mutation (400) creates no committed AreaVersion
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AreaAuditIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("snapland_gis")
            .withUsername("snapland")
            .withPassword("snapland");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DevProperties devProperties;

    @Autowired
    private PrototypeProperties prototypeProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    private UUID userAId;
    private String userAToken;
    private UUID userBId;
    private String userBToken;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = prototypeProperties.workspaceId();

        // User A = the seeded dev user; already present in the database
        userAId = devProperties.userId();
        userAToken = TestJwtUtil.generateToken(jwtSecret, userAId);

        // User B = a second distinct user, inserted fresh each test to prove
        // that created_by reflects the caller, not the Area creator
        userBId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, created_at) VALUES (?::uuid, 'User B', ?, NOW())",
                userBId.toString(), "userb-" + userBId + "@test.local");
        userBToken = TestJwtUtil.generateToken(jwtSecret, userBId);

        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");
    }

    // --- helpers ---

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private GeoJsonGeometry squarePolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.50, 32.70), List.of(35.55, 32.70),
                List.of(35.55, 32.75), List.of(35.50, 32.75),
                List.of(35.50, 32.70));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private GeoJsonGeometry altPolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.60, 32.80), List.of(35.65, 32.80),
                List.of(35.65, 32.85), List.of(35.60, 32.85),
                List.of(35.60, 32.80));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private GeoJsonGeometry selfIntersectingPolygon() {
        // Bowtie shape — geometrically invalid; rejected with 400 INVALID_GEOMETRY
        List<List<Double>> ring = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private String areasUrl() {
        return "/workspaces/" + workspaceId + "/areas";
    }

    private String versionsUrl(UUID areaId) {
        return areasUrl() + "/" + areaId + "/versions";
    }

    private UUID createArea(String token, String name, GeoJsonGeometry geometry) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest(name, geometry), auth(token)),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void updateArea(String token, UUID areaId, int expectedVersion,
                            String name, GeoJsonGeometry geometry) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl() + "/" + areaId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(expectedVersion, name, geometry), auth(token)),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteArea(String token, UUID areaId, int expectedVersion) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl() + "/" + areaId + "?expectedVersion=" + expectedVersion,
                HttpMethod.DELETE, new HttpEntity<>(null, auth(token)), AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void restoreArea(String token, UUID areaId, int targetVersion, int expectedVersion) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl() + "/" + areaId + "/versions/" + targetVersion + "/restore",
                HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(expectedVersion), auth(token)),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AreaVersionResponse[] getVersions(UUID areaId) {
        ResponseEntity<AreaVersionResponse[]> response = restTemplate.exchange(
                versionsUrl(areaId), HttpMethod.GET,
                new HttpEntity<>(null, auth(userAToken)), AreaVersionResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // --- TC-AUDIT-001: CREATE records actor, changeType, version, and timestamp ---

    @Test
    void createArea_auditVersion_recordsActorChangeTypeVersionAndTimestamp() {
        UUID areaId = createArea(userAToken, "Audit Area", squarePolygon());

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(1);
        AreaVersionResponse v1 = versions[0];
        assertThat(v1.changeType()).isEqualTo("CREATED");
        assertThat(v1.createdBy()).isEqualTo(userAId);
        assertThat(v1.createdAt()).isNotNull();
        assertThat(v1.version()).isEqualTo(1);
    }

    // --- TC-AUDIT-002: UPDATE records the calling actor (distinct from the Area creator) ---

    @Test
    void updateArea_auditVersion_recordsCallerNotCreatorAndResultingVersion() {
        // User A creates the area; User B performs the update
        UUID areaId = createArea(userAToken, "Original", squarePolygon());
        updateArea(userBToken, areaId, 1, "Updated by B", altPolygon());

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(2);

        AreaVersionResponse v2 = versions[0]; // newest first
        assertThat(v2.changeType()).isEqualTo("UPDATED");
        assertThat(v2.createdBy()).isEqualTo(userBId);  // User B is the actor
        assertThat(v2.version()).isEqualTo(2);

        // Original creator is still recorded on v1
        assertThat(versions[1].createdBy()).isEqualTo(userAId);
    }

    // --- TC-AUDIT-003: DELETE records actor, changeType=DELETED, status=DELETED ---

    @Test
    void deleteArea_auditVersion_recordsCallerChangeTypeAndDeletedStatus() {
        // User A creates; User B deletes
        UUID areaId = createArea(userAToken, "To Delete", squarePolygon());
        deleteArea(userBToken, areaId, 1);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(2);

        AreaVersionResponse deleteVersion = versions[0];
        assertThat(deleteVersion.changeType()).isEqualTo("DELETED");
        assertThat(deleteVersion.status()).isEqualTo("DELETED");
        assertThat(deleteVersion.createdBy()).isEqualTo(userBId);  // User B is the actor
    }

    // --- TC-AUDIT-004: RESTORE records the calling actor and the resulting version ---

    @Test
    void restoreArea_auditVersion_recordsCallerAndResultingVersion() {
        // User A creates (v1) and updates (v2); User B restores to v1
        UUID areaId = createArea(userAToken, "Original", squarePolygon());
        updateArea(userAToken, areaId, 1, "Updated", altPolygon());
        restoreArea(userBToken, areaId, 1, 2);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(3);

        AreaVersionResponse restoreVersion = versions[0];
        assertThat(restoreVersion.changeType()).isEqualTo("RESTORED");
        assertThat(restoreVersion.createdBy()).isEqualTo(userBId);  // User B is the actor
        assertThat(restoreVersion.version()).isEqualTo(3);
    }

    // --- TC-AUDIT-005: Failed mutation (400) creates no committed AreaVersion ---

    @Test
    void failedMutation_doesNotCreateCommittedAuditVersion() {
        UUID areaId = createArea(userAToken, "Area", squarePolygon());

        long versionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version", Long.class);

        // Update with a self-intersecting (invalid) polygon → 400 INVALID_GEOMETRY
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl() + "/" + areaId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Bad shape", selfIntersectingPolygon()),
                        auth(userAToken)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");

        long versionsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version", Long.class);
        assertThat(versionsAfter).isEqualTo(versionsBefore);
    }
}
