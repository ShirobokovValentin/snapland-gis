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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AreaVersionHistoryIntegrationTest {

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

    private String devToken;
    private UUID workspaceId;

    @BeforeEach
    void cleanAreas() {
        workspaceId = prototypeProperties.workspaceId();
        devToken = TestJwtUtil.generateToken(jwtSecret, devProperties.userId());
        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");
    }

    // --- helpers ---

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(devToken);
        return headers;
    }

    private GeoJsonGeometry squarePolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.50, 32.70),
                List.of(35.55, 32.70),
                List.of(35.55, 32.75),
                List.of(35.50, 32.75),
                List.of(35.50, 32.70));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private GeoJsonGeometry altPolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.60, 32.80),
                List.of(35.65, 32.80),
                List.of(35.65, 32.85),
                List.of(35.60, 32.85),
                List.of(35.60, 32.80));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private String areasUrl() {
        return "/workspaces/" + workspaceId + "/areas";
    }

    private String versionsUrl(UUID areaId) {
        return areasUrl() + "/" + areaId + "/versions";
    }

    private String restoreUrl(UUID areaId, int targetVersion) {
        return areasUrl() + "/" + areaId + "/versions/" + targetVersion + "/restore";
    }

    private UUID createArea(String name, GeoJsonGeometry geometry) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest(name, geometry), authHeaders()),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void updateArea(UUID areaId, int expectedVersion, String name, GeoJsonGeometry geometry) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl() + "/" + areaId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(expectedVersion, name, geometry), authHeaders()),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteArea(UUID areaId, int expectedVersion) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl() + "/" + areaId + "?expectedVersion=" + expectedVersion,
                HttpMethod.DELETE, new HttpEntity<>(null, authHeaders()), AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void restoreArea(UUID areaId, int targetVersion, int expectedVersion) {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                restoreUrl(areaId, targetVersion), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(expectedVersion), authHeaders()),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AreaVersionResponse[] getVersions(UUID areaId) {
        ResponseEntity<AreaVersionResponse[]> response = restTemplate.exchange(
                versionsUrl(areaId), HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), AreaVersionResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // --- tests ---

    @Test
    void getVersions_returnsVersionsOrderedDescending() {
        UUID areaId = createArea("v1", squarePolygon());
        updateArea(areaId, 1, "v2", squarePolygon());
        updateArea(areaId, 2, "v3", squarePolygon());

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(3);
        assertThat(versions[0].version()).isEqualTo(3);
        assertThat(versions[1].version()).isEqualTo(2);
        assertThat(versions[2].version()).isEqualTo(1);
    }

    @Test
    void getVersions_returnsAllChangeTypes() {
        UUID areaId = createArea("Initial", squarePolygon());
        updateArea(areaId, 1, "Updated", altPolygon());
        deleteArea(areaId, 2);
        restoreArea(areaId, 1, 3);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(4);
        assertThat(versions[0].changeType()).isEqualTo("RESTORED");
        assertThat(versions[1].changeType()).isEqualTo("DELETED");
        assertThat(versions[2].changeType()).isEqualTo("UPDATED");
        assertThat(versions[3].changeType()).isEqualTo("CREATED");
    }

    @Test
    void getVersions_snapshotContainsCorrectNameGeometryAreaKm2StatusChangeType() {
        UUID areaId = createArea("First", squarePolygon());
        updateArea(areaId, 1, "Second", altPolygon());

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(2);

        AreaVersionResponse v2 = versions[0];
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.name()).isEqualTo("Second");
        assertThat(v2.status()).isEqualTo("ACTIVE");
        assertThat(v2.changeType()).isEqualTo("UPDATED");
        assertThat(v2.geometry()).isNotNull();
        assertThat(v2.geometry().type()).isEqualTo("Polygon");
        assertThat(v2.areaKm2()).isPositive();
        assertThat(v2.createdBy()).isNotNull();
        assertThat(v2.createdAt()).isNotNull();

        AreaVersionResponse v1 = versions[1];
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.name()).isEqualTo("First");
        assertThat(v1.status()).isEqualTo("ACTIVE");
        assertThat(v1.changeType()).isEqualTo("CREATED");

        // geometries should differ between v1 (squarePolygon) and v2 (altPolygon)
        assertThat(v1.geometry().coordinates()).isNotEqualTo(v2.geometry().coordinates());
    }

    @Test
    void getVersions_returns404ForUnknownArea() {
        ResponseEntity<Map> response = restTemplate.exchange(
                versionsUrl(UUID.randomUUID()), HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void getVersions_returns404ForAreaInOtherWorkspace() {
        UUID otherWorkspaceId = UUID.randomUUID();
        UUID userId = devProperties.userId();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_by) VALUES (?::uuid, 'Other', ?::uuid)",
                otherWorkspaceId.toString(), userId.toString());

        UUID otherAreaId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO area (id, workspace_id, name, geometry, area_km2, status, current_version, created_by, created_at) " +
                "VALUES (?::uuid, ?::uuid, 'A', ST_GeomFromText('POLYGON((35.50 32.70, 35.55 32.70, 35.55 32.75, 35.50 32.75, 35.50 32.70))', 4326), 1.0, 'ACTIVE', 1, ?::uuid, NOW())",
                otherAreaId.toString(), otherWorkspaceId.toString(), userId.toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                versionsUrl(otherAreaId), HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void getVersions_returns404ForUnknownWorkspace() {
        UUID unknownWorkspace = UUID.randomUUID();
        UUID areaId = createArea("Test", squarePolygon());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + unknownWorkspace + "/areas/" + areaId + "/versions",
                HttpMethod.GET, new HttpEntity<>(null, authHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void getVersions_worksForDeletedArea() {
        UUID areaId = createArea("To delete", squarePolygon());
        deleteArea(areaId, 1);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(2);
        assertThat(versions[0].changeType()).isEqualTo("DELETED");
        assertThat(versions[0].status()).isEqualTo("DELETED");
        assertThat(versions[1].changeType()).isEqualTo("CREATED");
    }

    @Test
    void restoreFromHistory_producesNewRestoredVersionAtTopOfHistory() {
        UUID areaId = createArea("v1", squarePolygon());
        updateArea(areaId, 1, "v2", altPolygon());
        // restore to v1 (expectedVersion=2)
        restoreArea(areaId, 1, 2);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(3);
        assertThat(versions[0].version()).isEqualTo(3);
        assertThat(versions[0].changeType()).isEqualTo("RESTORED");
        assertThat(versions[0].name()).isEqualTo("v1");
        assertThat(versions[0].status()).isEqualTo("ACTIVE");
    }

    @Test
    void restoreFromHistory_previousVersionsRemainUnchanged() {
        UUID areaId = createArea("v1", squarePolygon());
        updateArea(areaId, 1, "v2", altPolygon());
        restoreArea(areaId, 1, 2);

        AreaVersionResponse[] versions = getVersions(areaId);

        assertThat(versions).hasSize(3);

        // v2 snapshot is unchanged
        assertThat(versions[1].version()).isEqualTo(2);
        assertThat(versions[1].changeType()).isEqualTo("UPDATED");
        assertThat(versions[1].name()).isEqualTo("v2");

        // v1 snapshot is unchanged
        assertThat(versions[2].version()).isEqualTo(1);
        assertThat(versions[2].changeType()).isEqualTo("CREATED");
        assertThat(versions[2].name()).isEqualTo("v1");
    }

    @Test
    void restoreFromHistory_returns409VersionConflict_whenExpectedVersionIsStale() {
        UUID areaId = createArea("v1", squarePolygon());
        updateArea(areaId, 1, "v2", altPolygon());
        // currentVersion is now 2; pass stale expectedVersion=1
        ResponseEntity<Map> response = restTemplate.exchange(
                restoreUrl(areaId, 1), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(1), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");
    }
}
