package com.snapland.gis;

import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-PURGE-001  Permanently deleting a DELETED Area physically removes the Area row.
 * TC-PURGE-002  Permanent delete removes associated AreaVersion history.
 * TC-PURGE-003  Permanent delete of an ACTIVE Area is rejected with 409 AREA_NOT_DELETED.
 * TC-PURGE-004  Permanent delete requires authentication.
 * TC-PURGE-005  Area from an unknown workspace cannot be permanently deleted.
 * TC-PURGE-006  Second permanent delete of an already removed Area returns 404 AREA_NOT_FOUND.
 * TC-PURGE-007  ABA-style stale version: DELETED v5 → RESTORED v6 → DELETED v7
 *               → permanent-delete with expectedVersion=5 → 409 VERSION_CONFLICT;
 *               Area v7 remains DELETED and recoverable.
 * TC-PURGE-008  Unknown workspace returns 404 WORKSPACE_NOT_FOUND.
 * TC-PURGE-009  Stale expectedVersion returns 409 VERSION_CONFLICT.
 * TC-PURGE-010  Permanent delete returns the last DELETED state as the response body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PermanentDeleteIntegrationTest extends IntegrationTestBase {

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

    private GeoJsonGeometry squarePolygon() { return TestFixtures.squarePolygon(); }

    private UUID createAndDelete(String name) {
        ResponseEntity<AreaResponse> created = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest(name, squarePolygon()), auth()),
                AreaResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID areaId = created.getBody().id();

        ResponseEntity<AreaResponse> deleted = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "?expectedVersion=1",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), AreaResponse.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        return areaId;
    }

    private ResponseEntity<AreaResponse> doPermanentDelete(UUID areaId, int expectedVersion) {
        return restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/permanent?expectedVersion=" + expectedVersion,
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), AreaResponse.class);
    }

    private ResponseEntity<Map> doPermanentDeleteForError(UUID areaId, int expectedVersion) {
        return restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/permanent?expectedVersion=" + expectedVersion,
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), Map.class);
    }

    // --- TC-PURGE-001 ---

    @Test
    void permanentDelete_physicallyRemovesAreaRow() {
        UUID areaId = createAndDelete("Deleted Area");

        ResponseEntity<AreaResponse> response = doPermanentDelete(areaId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area WHERE id = ?::uuid", Long.class, areaId.toString());
        assertThat(count).isZero();
    }

    // --- TC-PURGE-002 ---

    @Test
    void permanentDelete_removesAllAreaVersionHistory() {
        UUID areaId = createAndDelete("Versioned Area");

        doPermanentDelete(areaId, 2);

        Long versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version WHERE area_id = ?::uuid", Long.class, areaId.toString());
        assertThat(versionCount).isZero();
    }

    // --- TC-PURGE-003 ---

    @Test
    void permanentDelete_ofActiveArea_returns409AreaNotDeleted() {
        ResponseEntity<AreaResponse> created = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Active Area", squarePolygon()), auth()),
                AreaResponse.class);
        UUID areaId = created.getBody().id();

        ResponseEntity<Map> response = doPermanentDeleteForError(areaId, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_DELETED");
    }

    // --- TC-PURGE-004 ---

    @Test
    void permanentDelete_requiresAuthentication() {
        UUID areaId = createAndDelete("Auth Test");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/permanent?expectedVersion=2",
                HttpMethod.DELETE, new HttpEntity<>(null, new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- TC-PURGE-005 ---

    @Test
    void permanentDelete_areaFromOtherWorkspace_returns404() {
        UUID areaId = createAndDelete("Cross-workspace Area");

        UUID otherWorkspace = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_by) VALUES (?::uuid, ?, ?::uuid)",
                otherWorkspace.toString(), "Other WS", devProperties.userId().toString());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + otherWorkspace + "/areas/" + areaId + "/permanent?expectedVersion=2",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    // --- TC-PURGE-006 ---

    @Test
    void permanentDelete_alreadyRemovedArea_returns404() {
        UUID areaId = createAndDelete("Double Delete");
        doPermanentDelete(areaId, 2);

        ResponseEntity<Map> response = doPermanentDeleteForError(areaId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("AREA_NOT_FOUND");
    }

    // --- TC-PURGE-007: ABA stale-version case ---

    @Test
    void permanentDelete_aba_staleVersion_returns409VersionConflict_andAreaRemainsRecoverable() {
        // Create at v1
        ResponseEntity<AreaResponse> created = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("ABA Area", squarePolygon()), auth()),
                AreaResponse.class);
        UUID areaId = created.getBody().id();

        // Delete → v2 DELETED
        restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "?expectedVersion=1",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), AreaResponse.class);

        // Restore → v3 ACTIVE
        restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/versions/1/restore",
                HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(2), auth()),
                AreaResponse.class);

        // Delete again → v4 DELETED
        restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "?expectedVersion=3",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), AreaResponse.class);

        // Attempt permanent delete with the stale expectedVersion=2
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas/" + areaId + "/permanent?expectedVersion=2",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");

        // Area must still exist at v4 DELETED
        Long areaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area WHERE id = ?::uuid AND status = 'DELETED' AND current_version = 4",
                Long.class, areaId.toString());
        assertThat(areaCount).isEqualTo(1);

        // AreaVersion history must still be intact (4 versions)
        Long versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version WHERE area_id = ?::uuid",
                Long.class, areaId.toString());
        assertThat(versionCount).isEqualTo(4);
    }

    // --- TC-PURGE-008 ---

    @Test
    void permanentDelete_unknownWorkspace_returns404() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID() + "/areas/" + UUID.randomUUID() + "/permanent?expectedVersion=1",
                HttpMethod.DELETE, new HttpEntity<>(null, auth()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    // --- TC-PURGE-009 ---

    @Test
    void permanentDelete_staleExpectedVersion_returns409VersionConflict() {
        UUID areaId = createAndDelete("Version Conflict Area"); // now at v2 DELETED

        ResponseEntity<Map> response = doPermanentDeleteForError(areaId, 99);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERSION_CONFLICT");

        // Area must still exist
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area WHERE id = ?::uuid", Long.class, areaId.toString());
        assertThat(count).isEqualTo(1);
    }

    // --- TC-PURGE-010 ---

    @Test
    void permanentDelete_returnsLastDeletedStateAsResponseBody() {
        UUID areaId = createAndDelete("Response Test");

        ResponseEntity<AreaResponse> response = doPermanentDelete(areaId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AreaResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(areaId);
        assertThat(body.status()).isEqualTo("DELETED");
        assertThat(body.currentVersion()).isEqualTo(2);
    }
}
