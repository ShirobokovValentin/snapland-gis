package com.snapland.gis;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.DeletedAreaResponse;
import com.snapland.gis.area.dto.GeoJsonGeometry;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-DELETED-001  Soft-deleted Area appears in Deleted Areas API.
 * TC-DELETED-002  ACTIVE Area does not appear in Deleted Areas API.
 * TC-DELETED-003  Normal viewport query continues to exclude a DELETED Area.
 * TC-DELETED-004  Restore removes Area from Deleted Areas API and Area returns to ACTIVE state.
 * TC-DELETED-005  deletedAt in the response is the timestamp of the DELETED AreaVersion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DeletedAreasIntegrationTest {

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

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private DevProperties devProperties;
    @Autowired private PrototypeProperties prototypeProperties;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${app.auth.jwt-secret}") private String jwtSecret;

    private String devToken;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = prototypeProperties.workspaceId();
        devToken = TestJwtUtil.generateToken(jwtSecret, devProperties.userId());
        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");
    }

    // --- helpers ---

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(devToken);
        return h;
    }

    private GeoJsonGeometry squarePolygon() {
        List<List<Double>> ring = List.of(
                List.of(35.50, 32.70), List.of(35.55, 32.70),
                List.of(35.55, 32.75), List.of(35.50, 32.75),
                List.of(35.50, 32.70));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private UUID createArea(String name) {
        ResponseEntity<AreaResponse> r = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest(name, squarePolygon()), auth()),
                AreaResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().id();
    }

    private void softDelete(UUID areaId, int expectedVersion) {
        ResponseEntity<AreaResponse> r = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "?expectedVersion=" + expectedVersion,
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), AreaResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private DeletedAreaResponse[] getDeletedAreas() {
        ResponseEntity<DeletedAreaResponse[]> r = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/deleted", HttpMethod.GET,
                new HttpEntity<>(null, auth()), DeletedAreaResponse[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    // --- TC-DELETED-001 ---

    @Test
    void softDeletedArea_appearsInDeletedAreasApi() {
        UUID areaId = createArea("Park");
        softDelete(areaId, 1);

        DeletedAreaResponse[] deleted = getDeletedAreas();

        assertThat(deleted).hasSize(1);
        assertThat(deleted[0].id()).isEqualTo(areaId);
        assertThat(deleted[0].name()).isEqualTo("Park");
        assertThat(deleted[0].workspaceId()).isEqualTo(workspaceId);
        assertThat(deleted[0].currentVersion()).isEqualTo(2);
    }

    // --- TC-DELETED-002 ---

    @Test
    void activeArea_doesNotAppearInDeletedAreasApi() {
        createArea("Active Area");

        DeletedAreaResponse[] deleted = getDeletedAreas();

        assertThat(deleted).isEmpty();
    }

    // --- TC-DELETED-003 ---

    @Test
    void deletedArea_isExcludedFromViewportQuery() {
        UUID areaId = createArea("Viewport Test");
        softDelete(areaId, 1);

        String bbox = "35.45,32.65,35.60,32.80";
        ResponseEntity<AreaResponse[]> viewportResponse = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas?bbox=" + bbox, HttpMethod.GET,
                new HttpEntity<>(null, auth()), AreaResponse[].class);

        assertThat(viewportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(viewportResponse.getBody()).isEmpty();
    }

    // --- TC-DELETED-004 ---

    @Test
    void restoredArea_disappearsFromDeletedAreasApi() {
        UUID areaId = createArea("Restored Area");
        softDelete(areaId, 1); // v2 DELETED

        restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/versions/1/restore",
                HttpMethod.POST,
                new HttpEntity<>(new com.snapland.gis.area.dto.RestoreAreaRequest(2), auth()),
                AreaResponse.class);

        DeletedAreaResponse[] deleted = getDeletedAreas();

        assertThat(Arrays.stream(deleted).noneMatch(d -> d.id().equals(areaId))).isTrue();
    }

    // --- TC-DELETED-005 ---

    @Test
    void deletedAt_matchesAreaVersionTimestamp() {
        UUID areaId = createArea("Timestamp Test");
        softDelete(areaId, 1);

        DeletedAreaResponse[] deleted = getDeletedAreas();

        assertThat(deleted).hasSize(1);
        assertThat(deleted[0].deletedAt()).isNotNull();

        // deletedAt must match the created_at of the DELETED AreaVersion
        java.time.Instant versionTimestamp = jdbcTemplate.queryForObject(
                "SELECT created_at FROM area_version WHERE area_id = ?::uuid AND version = 2",
                java.time.Instant.class, areaId.toString());
        assertThat(deleted[0].deletedAt()).isEqualTo(versionTimestamp);
    }
}
