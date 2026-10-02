package com.snapland.gis;

import com.snapland.gis.area.AreaRepository;
import com.snapland.gis.area.AreaVersionRepository;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AreaUpdateIntegrationTest {

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

    private String areaUrl(UUID areaId) {
        return areasUrl() + "/" + areaId;
    }

    private UUID createArea() {
        ResponseEntity<AreaResponse> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Initial", squarePolygon()), authHeaders()),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ResponseEntity<AreaResponse> doUpdate(UUID areaId, UpdateAreaRequest request) {
        return restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(request, authHeaders()), AreaResponse.class);
    }

    // --- update tests ---

    @Test
    void updateArea_returns200WithUpdatedFields() {
        UUID areaId = createArea();

        ResponseEntity<AreaResponse> response = doUpdate(areaId,
                new UpdateAreaRequest(1, "Updated Name", altPolygon()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AreaResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(areaId);
        assertThat(body.name()).isEqualTo("Updated Name");
        assertThat(body.currentVersion()).isEqualTo(2);
        assertThat(body.areaKm2()).isPositive();
        assertThat(body.geometry().type()).isEqualTo("Polygon");
    }

    @Test
    void updateArea_incrementsVersion() {
        UUID areaId = createArea();

        doUpdate(areaId, new UpdateAreaRequest(1, "V2", altPolygon()));
        ResponseEntity<AreaResponse> response = doUpdate(areaId,
                new UpdateAreaRequest(2, "V3", squarePolygon()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currentVersion()).isEqualTo(3);
    }

    @Test
    void updateArea_persistsVersionWithChangeTypeUpdated() {
        UUID areaId = createArea();

        doUpdate(areaId, new UpdateAreaRequest(1, "Updated", altPolygon()));

        var versions = areaVersionRepository.findAll();
        assertThat(versions).hasSize(2);
        var updatedVersion = versions.stream()
                .filter(v -> v.getVersion() == 2)
                .findFirst();
        assertThat(updatedVersion).isPresent();
        assertThat(updatedVersion.get().getChangeType().name()).isEqualTo("UPDATED");
        assertThat(updatedVersion.get().getName()).isEqualTo("Updated");
    }

    @Test
    void updateArea_returns409VersionConflict_whenExpectedVersionIsStale() {
        UUID areaId = createArea();
        doUpdate(areaId, new UpdateAreaRequest(1, "V2", altPolygon()));

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Conflict", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");
        Map<?, ?> details = (Map<?, ?>) response.getBody().get("details");
        assertThat(details.get("expectedVersion")).isEqualTo(1);
        assertThat(details.get("currentVersion")).isEqualTo(2);
    }

    @Test
    void updateArea_versionConflictDetails_containsAreaId() {
        UUID areaId = createArea();
        doUpdate(areaId, new UpdateAreaRequest(1, "V2", altPolygon()));

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Conflict", squarePolygon()), authHeaders()),
                Map.class);

        Map<?, ?> details = (Map<?, ?>) response.getBody().get("details");
        assertThat(details.get("areaId").toString()).isEqualTo(areaId.toString());
    }

    @Test
    void updateArea_returns404ForUnknownArea() {
        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(UUID.randomUUID()), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void updateArea_returns404ForAreaInOtherWorkspace() {
        UUID areaId = createArea();

        // Insert a second workspace so the workspace guard passes and the cross-workspace path is exercised
        UUID otherWorkspace = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_by) VALUES (?::uuid, ?, ?::uuid)",
                otherWorkspace.toString(), "Other Workspace", devProperties.userId().toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + otherWorkspace + "/areas/" + areaId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void updateArea_returns409ForDeletedArea() {
        UUID areaId = createArea();
        jdbcTemplate.update("UPDATE area SET status = 'DELETED' WHERE id = ?::uuid", areaId.toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_DELETED");
    }

    @Test
    void updateArea_returns404ForUnknownWorkspace() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas/" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "X", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void updateArea_rejectsBlankName() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void updateArea_rejectsInvalidPolygon() {
        UUID areaId = createArea();
        List<List<Double>> bowtie = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Bad", new GeoJsonGeometry("Polygon", List.of(bowtie))), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");
        assertThat(areaVersionRepository.findAll()).hasSize(1);
    }

    @Test
    void updateArea_rejectsNullExpectedVersion() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(null, "Name", squarePolygon()), authHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void updateArea_concurrent_exactlyOneSucceeds() throws Exception {
        UUID areaId = createArea();

        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ResponseEntity<Map> response = restTemplate.exchange(
                        areaUrl(areaId), HttpMethod.PUT,
                        new HttpEntity<>(new UpdateAreaRequest(1, "Thread-" + idx, altPolygon()), authHeaders()),
                        Map.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    successCount.incrementAndGet();
                } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictCount.incrementAndGet();
                }
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);

        int finalVersion = jdbcTemplate.queryForObject(
                "SELECT current_version FROM area WHERE id = ?::uuid",
                Integer.class, areaId.toString());
        assertThat(finalVersion).isEqualTo(2);
    }
}
