package com.snapland.gis;

import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.workspace.dto.CreateWorkspaceRequest;
import com.snapland.gis.workspace.dto.WorkspaceResponse;
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
 * Integration tests for workspace management.
 *
 * TC-WS-LIST-001   Unauthenticated GET /workspaces → 401
 * TC-WS-LIST-002   Authenticated GET /workspaces with no workspaces → 200 []
 * TC-WS-LIST-003   Authenticated GET /workspaces after creating → 200 includes workspace
 * TC-WS-CREATE-001 POST /workspaces valid → 201 with workspace response
 * TC-WS-CREATE-002 POST /workspaces blank name → 400 INVALID_REQUEST
 * TC-WS-CREATE-003 POST /workspaces name too long → 400 INVALID_REQUEST
 * TC-WS-CREATE-004 Unauthenticated POST → 401
 * TC-WS-DELETE-001 Creator can delete workspace → 204
 * TC-WS-DELETE-002 Non-creator cannot delete → 403 WORKSPACE_ACCESS_DENIED
 * TC-WS-DELETE-003 Unknown workspace → 404 WORKSPACE_NOT_FOUND
 * TC-WS-DELETE-004 Delete cascades areas → area table empty after delete
 * TC-WS-DELETE-005 Unauthenticated DELETE → 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WorkspaceIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    private String devToken;
    private UUID secondUserId;
    private String secondToken;

    @BeforeEach
    void setUp() {
        devToken = TestJwtUtil.generateToken(jwtSecret, devProperties.userId());

        secondUserId = UUID.randomUUID();
        secondToken = TestJwtUtil.generateToken(jwtSecret, secondUserId);
        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, created_at)
                VALUES (?::uuid, ?, ?, NOW())
                ON CONFLICT (id) DO NOTHING
                """, secondUserId.toString(), "Second User", "second-" + secondUserId + "@snapland.local");

        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");
        jdbcTemplate.update("DELETE FROM workspace_member");
        jdbcTemplate.update("DELETE FROM workspace");
    }

    // --- TC-WS-LIST-001 ---

    @Test
    void unauthenticated_listWorkspaces_returns401() {
        ResponseEntity<Map> r = restTemplate.getForEntity("/workspaces", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- TC-WS-LIST-002 ---

    @Test
    void authenticated_listWorkspaces_emptyDb_returns200EmptyList() {
        ResponseEntity<List> r = restTemplate.exchange(
                "/workspaces", HttpMethod.GET, new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEmpty();
    }

    // --- TC-WS-LIST-003 ---

    @Test
    void authenticated_listWorkspaces_afterCreate_includesWorkspace() {
        createWorkspace("My Workspace", devToken);

        ResponseEntity<List> r = restTemplate.exchange(
                "/workspaces", HttpMethod.GET, new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        Map<?, ?> ws = (Map<?, ?>) r.getBody().get(0);
        assertThat(ws.get("name")).isEqualTo("My Workspace");
        assertThat(ws.get("createdById")).isEqualTo(devProperties.userId().toString());
    }

    // --- TC-WS-CREATE-001 ---

    @Test
    void createWorkspace_valid_returns201WithResponse() {
        ResponseEntity<WorkspaceResponse> r = restTemplate.exchange(
                "/workspaces", HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest("Alpha"), auth(devToken)),
                WorkspaceResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WorkspaceResponse body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.name()).isEqualTo("Alpha");
        assertThat(body.createdById()).isEqualTo(devProperties.userId());
        assertThat(body.createdAt()).isNotNull();
    }

    // --- TC-WS-CREATE-002 ---

    @Test
    void createWorkspace_blankName_returns400() {
        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces", HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest(""), auth(devToken)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    // --- TC-WS-CREATE-003 ---

    @Test
    void createWorkspace_nameTooLong_returns400() {
        String longName = "A".repeat(256);
        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces", HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest(longName), auth(devToken)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    // --- TC-WS-CREATE-004 ---

    @Test
    void unauthenticated_createWorkspace_returns401() {
        ResponseEntity<Map> r = restTemplate.postForEntity(
                "/workspaces", new CreateWorkspaceRequest("Beta"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- TC-WS-DELETE-001 ---

    @Test
    void creator_deleteWorkspace_returns204() {
        WorkspaceResponse created = createWorkspace("ToDelete", devToken);

        ResponseEntity<Void> r = restTemplate.exchange(
                "/workspaces/" + created.id(), HttpMethod.DELETE,
                new HttpEntity<>(auth(devToken)), Void.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?::uuid", Integer.class, created.id().toString());
        assertThat(count).isEqualTo(0);
    }

    // --- TC-WS-DELETE-002 ---

    @Test
    void nonCreator_deleteWorkspace_returns403() {
        WorkspaceResponse created = createWorkspace("Protected", devToken);

        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces/" + created.id(), HttpMethod.DELETE,
                new HttpEntity<>(auth(secondToken)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code")).isEqualTo("WORKSPACE_ACCESS_DENIED");
    }

    // --- TC-WS-DELETE-003 ---

    @Test
    void deleteWorkspace_unknownId_returns404() {
        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces/" + UUID.randomUUID(), HttpMethod.DELETE,
                new HttpEntity<>(auth(devToken)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    // --- TC-WS-DELETE-004 ---

    @Test
    void deleteWorkspace_cascadesAreas() {
        WorkspaceResponse ws = createWorkspace("WithAreas", devToken);

        jdbcTemplate.update("""
                WITH area_insert AS (
                    INSERT INTO area (id, workspace_id, name, geometry, area_km2, status, current_version, created_by, created_at)
                    VALUES (gen_random_uuid(), ?::uuid, 'Test Area',
                            ST_GeomFromText('POLYGON((10 50, 11 50, 11 51, 10 51, 10 50))', 4326),
                            1.0, 'ACTIVE', 1, ?::uuid, NOW())
                    RETURNING id, created_by, created_at
                )
                INSERT INTO area_version (id, area_id, version, name, geometry, area_km2, status, change_type, created_by, created_at)
                SELECT gen_random_uuid(), ai.id, 1, 'Test Area',
                       ST_GeomFromText('POLYGON((10 50, 11 50, 11 51, 10 51, 10 50))', 4326),
                       1.0, 'ACTIVE', 'CREATED', ai.created_by, ai.created_at
                FROM area_insert ai
                """, ws.id().toString(), devProperties.userId().toString());

        restTemplate.exchange("/workspaces/" + ws.id(), HttpMethod.DELETE,
                new HttpEntity<>(auth(devToken)), Void.class);

        Integer areaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area WHERE workspace_id = ?::uuid", Integer.class, ws.id().toString());
        assertThat(areaCount).isEqualTo(0);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version av JOIN area a ON av.area_id = a.id WHERE a.workspace_id = ?::uuid",
                Integer.class, ws.id().toString());
        assertThat(versionCount).isEqualTo(0);
    }

    // --- TC-WS-DELETE-005 ---

    @Test
    void unauthenticated_deleteWorkspace_returns401() {
        WorkspaceResponse created = createWorkspace("Unauthenticated", devToken);
        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces/" + created.id(), HttpMethod.DELETE,
                HttpEntity.EMPTY, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---

    private WorkspaceResponse createWorkspace(String name, String token) {
        ResponseEntity<WorkspaceResponse> r = restTemplate.exchange(
                "/workspaces", HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest(name), auth(token)),
                WorkspaceResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
