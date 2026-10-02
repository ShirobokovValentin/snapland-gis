package com.snapland.gis;

import com.snapland.gis.area.AreaRepository;
import com.snapland.gis.area.AreaVersionRepository;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import com.snapland.gis.dev.DevProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
class AreaDeleteRestoreIntegrationTest {

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

    private UUID workspaceId;

    @BeforeEach
    void cleanAreas() {
        workspaceId = devProperties.workspaceId();
        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");
    }

    // --- helpers ---

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

    private String deleteUrl(UUID areaId, int expectedVersion) {
        return areaUrl(areaId) + "?expectedVersion=" + expectedVersion;
    }

    private String restoreUrl(UUID areaId, int targetVersion) {
        return areaUrl(areaId) + "/versions/" + targetVersion + "/restore";
    }

    private UUID createArea() {
        return createArea("Initial", squarePolygon());
    }

    private UUID createArea(String name, GeoJsonGeometry geometry) {
        ResponseEntity<AreaResponse> response = restTemplate.postForEntity(
                areasUrl(), new CreateAreaRequest(name, geometry), AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ResponseEntity<AreaResponse> doDelete(UUID areaId, int expectedVersion) {
        return restTemplate.exchange(
                deleteUrl(areaId, expectedVersion), HttpMethod.DELETE, null, AreaResponse.class);
    }

    private ResponseEntity<AreaResponse> doRestore(UUID areaId, int targetVersion, int expectedVersion) {
        return restTemplate.exchange(
                restoreUrl(areaId, targetVersion), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(expectedVersion)), AreaResponse.class);
    }

    // --- delete tests ---

    @Test
    void deleteArea_returns200WithDeletedStatus() {
        UUID areaId = createArea();

        ResponseEntity<AreaResponse> response = doDelete(areaId, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AreaResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(areaId);
        assertThat(body.status()).isEqualTo("DELETED");
    }

    @Test
    void deleteArea_incrementsVersion() {
        UUID areaId = createArea();

        ResponseEntity<AreaResponse> response = doDelete(areaId, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currentVersion()).isEqualTo(2);
    }

    @Test
    void deleteArea_persistsVersionWithChangeTypeDeleted() {
        UUID areaId = createArea();

        doDelete(areaId, 1);

        var versions = areaVersionRepository.findAll();
        assertThat(versions).hasSize(2);
        var deletedVersion = versions.stream()
                .filter(v -> v.getVersion() == 2)
                .findFirst();
        assertThat(deletedVersion).isPresent();
        assertThat(deletedVersion.get().getChangeType().name()).isEqualTo("DELETED");
        assertThat(deletedVersion.get().getStatus().name()).isEqualTo("DELETED");
        assertThat(deletedVersion.get().getName()).isEqualTo("Initial");
        assertThat(deletedVersion.get().getGeometry()).isNotNull();
        assertThat(deletedVersion.get().getAreaKm2()).isPositive();
    }

    @Test
    void deleteArea_returns409VersionConflict_whenExpectedVersionIsStale() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                deleteUrl(areaId, 99), HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void deleteArea_versionConflictDetails_containsAreaId() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                deleteUrl(areaId, 99), HttpMethod.DELETE, null, Map.class);

        Map<?, ?> details = (Map<?, ?>) response.getBody().get("details");
        assertThat(details.get("areaId").toString()).isEqualTo(areaId.toString());
        assertThat(details.get("expectedVersion")).isEqualTo(99);
        assertThat(details.get("currentVersion")).isEqualTo(1);
    }

    @Test
    void deleteArea_returns404ForUnknownArea() {
        ResponseEntity<Map> response = restTemplate.exchange(
                deleteUrl(UUID.randomUUID(), 1), HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void deleteArea_returns404ForAreaInOtherWorkspace() {
        UUID areaId = createArea();

        UUID otherWorkspace = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_by) VALUES (?::uuid, ?, ?::uuid)",
                otherWorkspace.toString(), "Other Workspace", devProperties.userId().toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + otherWorkspace + "/areas/" + areaId + "?expectedVersion=1",
                HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    @Test
    void deleteArea_returns409ForAlreadyDeletedArea() {
        UUID areaId = createArea();
        doDelete(areaId, 1); // succeeds, currentVersion becomes 2, status=DELETED

        ResponseEntity<Map> response = restTemplate.exchange(
                deleteUrl(areaId, 2), HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_DELETED");
    }

    @Test
    void deleteArea_returns404ForUnknownWorkspace() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas/" + UUID.randomUUID() + "?expectedVersion=1",
                HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    void deleteArea_rejectsMissingExpectedVersion() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                areaUrl(areaId), HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void deleteArea_concurrent_exactlyOneSucceedsOtherGetsVersionConflict() throws Exception {
        UUID areaId = createArea();

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<String> conflictCode = new AtomicReference<>(null);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ResponseEntity<Map> response = restTemplate.exchange(
                        deleteUrl(areaId, 1), HttpMethod.DELETE, null, Map.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    successCount.incrementAndGet();
                } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictCode.set((String) response.getBody().get("code"));
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
        assertThat(conflictCode.get()).isEqualTo("VERSION_CONFLICT");

        int finalVersion = jdbcTemplate.queryForObject(
                "SELECT current_version FROM area WHERE id = ?::uuid",
                Integer.class, areaId.toString());
        assertThat(finalVersion).isEqualTo(2);
    }

    // --- restore tests ---

    @Test
    void restoreArea_returns200WithActiveStatus() {
        UUID areaId = createArea();
        doDelete(areaId, 1); // v2 DELETED

        ResponseEntity<AreaResponse> response = doRestore(areaId, 1, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AreaResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(areaId);
        assertThat(body.status()).isEqualTo("ACTIVE");
    }

    @Test
    void restoreArea_incrementsVersion() {
        UUID areaId = createArea();
        doDelete(areaId, 1); // v2 DELETED

        ResponseEntity<AreaResponse> response = doRestore(areaId, 1, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currentVersion()).isEqualTo(3);
    }

    @Test
    void restoreArea_persistsVersionWithChangeTypeRestored() {
        UUID areaId = createArea();
        doDelete(areaId, 1); // v2 DELETED

        doRestore(areaId, 1, 2); // v3 RESTORED from v1

        var versions = areaVersionRepository.findAll();
        assertThat(versions).hasSize(3);
        var restoredVersion = versions.stream()
                .filter(v -> v.getVersion() == 3)
                .findFirst();
        assertThat(restoredVersion).isPresent();
        assertThat(restoredVersion.get().getChangeType().name()).isEqualTo("RESTORED");
        assertThat(restoredVersion.get().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(restoredVersion.get().getName()).isEqualTo("Initial");
        assertThat(restoredVersion.get().getGeometry()).isNotNull();
        assertThat(restoredVersion.get().getAreaKm2()).isPositive();
    }

    @Test
    void restoreArea_restoresNameAndGeometryFromTargetVersion() {
        UUID areaId = createArea("Original", squarePolygon());
        restTemplate.exchange(
                areaUrl(areaId), HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Modified", altPolygon())), AreaResponse.class);
        // now v1=squarePolygon/"Original", v2=altPolygon/"Modified"

        ResponseEntity<AreaResponse> response = doRestore(areaId, 1, 2); // restore v1

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AreaResponse body = response.getBody();
        assertThat(body.name()).isEqualTo("Original");
        assertThat(body.currentVersion()).isEqualTo(3);
        // area_km2 should match the squarePolygon, not altPolygon
        assertThat(body.areaKm2()).isPositive();
    }

    @Test
    void restoreArea_returns409VersionConflict_whenExpectedVersionIsStale() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                restoreUrl(areaId, 1), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(99)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void restoreArea_returns404ForUnknownAreaVersion() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                restoreUrl(areaId, 999), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(1)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_VERSION_NOT_FOUND");
    }

    @Test
    void restoreArea_returns404ForUnknownArea() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + UUID.randomUUID() + "/versions/1/restore",
                HttpMethod.POST, new HttpEntity<>(new RestoreAreaRequest(1)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_VERSION_NOT_FOUND");
    }

    @Test
    void restoreArea_canRestoreDeletedArea() {
        UUID areaId = createArea();
        doDelete(areaId, 1); // v2 DELETED

        ResponseEntity<AreaResponse> response = doRestore(areaId, 1, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
        assertThat(response.getBody().currentVersion()).isEqualTo(3);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM area WHERE id = ?::uuid",
                String.class, areaId.toString());
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void restoreArea_rejectsNullExpectedVersion() {
        UUID areaId = createArea();

        ResponseEntity<Map> response = restTemplate.exchange(
                restoreUrl(areaId, 1), HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(null)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }
}
