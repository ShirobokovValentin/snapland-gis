package com.snapland.gis;

import com.snapland.gis.area.AreaRepository;
import com.snapland.gis.area.AreaVersionRepository;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AreaApiIntegrationTest {

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
    private AreaRepository areaRepository;

    @Autowired
    private AreaVersionRepository areaVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    private String devToken;
    private UUID workspaceId;

    @BeforeEach
    void cleanAreas() {
        workspaceId = devProperties.workspaceId();
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

    private String areasUrl() {
        return "/workspaces/" + workspaceId + "/areas";
    }

    // --- create area ---

    @Test
    void createArea_returns201WithPersistedArea() {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Test Area", squarePolygon()), authHeaders()),
                AreaResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AreaResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.name()).isEqualTo("Test Area");
        assertThat(body.status()).isEqualTo("ACTIVE");
        assertThat(body.currentVersion()).isEqualTo(1);
        assertThat(body.areaKm2()).isPositive();
        assertThat(body.geometry().type()).isEqualTo("Polygon");
        assertThat(body.workspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void createArea_persistsAreaVersionWithChangeTypeCreated() {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Versioned", squarePolygon()), authHeaders()),
                AreaResponse.class);
        UUID areaId = response.getBody().id();

        assertThat(areaRepository.findById(areaId)).isPresent();
        assertThat(areaVersionRepository.findAll())
                .hasSize(1)
                .first()
                .satisfies(v -> {
                    assertThat(v.getVersion()).isEqualTo(1);
                    assertThat(v.getChangeType().name()).isEqualTo("CREATED");
                    assertThat(v.getArea().getId()).isEqualTo(areaId);
                });
    }

    @Test
    void createArea_areaKm2MatchesPostgisCalculation() {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Area Calc", squarePolygon()), authHeaders()),
                AreaResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AreaResponse body = response.getBody();
        assertThat(body.areaKm2()).isPositive();

        BigDecimal postgisValue = jdbcTemplate.queryForObject(
                "SELECT CAST(ST_Area(geometry::geography) / 1000000.0 AS NUMERIC(20,6)) FROM area WHERE id = ?::uuid",
                BigDecimal.class, body.id().toString());

        assertThat(body.areaKm2()).isEqualByComparingTo(postgisValue);
    }

    @Test
    void createArea_rejectsInvalidPolygon() {
        // bowtie — self-intersects at (1,1)
        List<List<Double>> ring = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Bad", new GeoJsonGeometry("Polygon", List.of(ring))), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");
        assertThat(areaRepository.count()).isZero();
    }

    @Test
    void createArea_rejectsInteriorRings() {
        List<List<Double>> outer = List.of(
                List.of(35.50, 32.70), List.of(35.60, 32.70),
                List.of(35.60, 32.80), List.of(35.50, 32.80), List.of(35.50, 32.70));
        List<List<Double>> hole = List.of(
                List.of(35.52, 32.72), List.of(35.55, 32.72),
                List.of(35.55, 32.75), List.of(35.52, 32.75), List.of(35.52, 32.72));
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Holey", new GeoJsonGeometry("Polygon", List.of(outer, hole))), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");
    }

    @Test
    void createArea_rejectsUnclosedRing() {
        // 4 distinct positions — last does not repeat the first
        List<List<Double>> ring = List.of(
                List.of(35.50, 32.70),
                List.of(35.55, 32.70),
                List.of(35.55, 32.75),
                List.of(35.50, 32.75));
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Open Ring", new GeoJsonGeometry("Polygon", List.of(ring))), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");
        assertThat(areaRepository.count()).isZero();
    }

    @Test
    void createArea_rejectsBlankName() {
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void createArea_returns404ForUnknownWorkspace() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void createArea_persistedGeometryHasSrid4326() {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("SRID Check", squarePolygon()), authHeaders()),
                AreaResponse.class);
        UUID areaId = response.getBody().id();

        Integer srid = jdbcTemplate.queryForObject(
                "SELECT ST_SRID(geometry) FROM area WHERE id = ?::uuid",
                Integer.class, areaId.toString());

        assertThat(srid).isEqualTo(4326);
    }

    // --- viewport query ---

    @Test
    void getAreas_returnsIntersectingActiveArea() {
        restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Visible", squarePolygon()), authHeaders()),
                AreaResponse.class);

        ResponseEntity<AreaResponse[]> response = restTemplate.exchange(
                areasUrl() + "?bbox=35.49,32.69,35.56,32.76", HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), AreaResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].name()).isEqualTo("Visible");
    }

    @Test
    void getAreas_doesNotReturnAreaOutsideBbox() {
        restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Far Away", squarePolygon()), authHeaders()),
                AreaResponse.class);

        ResponseEntity<AreaResponse[]> response = restTemplate.exchange(
                areasUrl() + "?bbox=10.0,10.0,11.0,11.0", HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), AreaResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getAreas_doesNotReturnDeletedArea() {
        jdbcTemplate.update("""
                INSERT INTO area (id, workspace_id, name, geometry, area_km2, status, current_version, created_by)
                VALUES (gen_random_uuid(), ?::uuid, ?,
                        ST_GeomFromText('POLYGON ((35.50 32.70, 35.55 32.70, 35.55 32.75, 35.50 32.75, 35.50 32.70))', 4326),
                        17.0, 'DELETED', 1, ?::uuid)
                """,
                workspaceId.toString(), "Deleted Area", devProperties.userId().toString());

        ResponseEntity<AreaResponse[]> response = restTemplate.exchange(
                areasUrl() + "?bbox=35.49,32.69,35.56,32.76", HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), AreaResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getAreas_returns404ForUnknownWorkspace() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas?bbox=0,0,1,1", HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void getAreas_returns400ForMalformedBbox() {
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl() + "?bbox=not,valid,input", HttpMethod.GET,
                new HttpEntity<>(null, authHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void errorResponse_hasRequiredFields() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getBody()).containsKeys("code", "message", "details", "traceId");
    }

    // --- auth ---

    @Test
    void request_withoutToken_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("X", squarePolygon())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAreas_withoutToken_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl() + "?bbox=35.49,32.69,35.56,32.76", HttpMethod.GET,
                null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
