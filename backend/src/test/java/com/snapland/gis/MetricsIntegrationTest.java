package com.snapland.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.metrics.WebSocketSessionMetrics;
import com.snapland.gis.ratelimit.DrawingRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that custom Micrometer metrics are correctly incremented by
 * production code paths and that the Prometheus endpoint is accessible.
 *
 * Counter assertions are relative (before/after) so that test ordering and
 * shared-context counter accumulation do not affect correctness.
 *
 * TC-METRICS-001  CREATE → area.mutations.committed.total{change_type=CREATED} +1
 * TC-METRICS-002  UPDATE → area.mutations.committed.total{change_type=UPDATED} +1
 * TC-METRICS-003  DELETE → area.mutations.committed.total{change_type=DELETED} +1
 * TC-METRICS-004  RESTORE → area.mutations.committed.total{change_type=RESTORED} +1
 * TC-METRICS-005  VERSION_CONFLICT → area.version.conflict.total +1
 * TC-METRICS-006  Rate limit exceeded → drawing.rate.limit.rejected.total +1
 * TC-METRICS-007  Failed mutation (400) → no committed counter increment
 * TC-METRICS-008  GET /actuator/prometheus → 200, text/plain, expected metric present
 * TC-METRICS-009  Authenticated STOMP connect → websocket.connections.active +1
 * TC-METRICS-010  Disconnect → websocket.connections.active returns to previous value
 * TC-METRICS-011  Rejected (invalid JWT) connect → websocket.connections.active unchanged
 * TC-METRICS-012  Duplicate disconnect event → gauge does not go negative
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DevProperties devProperties;

    @Autowired
    private PrototypeProperties prototypeProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DrawingRateLimiter rateLimiter;

    @Autowired
    private WebSocketSessionMetrics wsMetrics;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    private String devToken;
    private UUID workspaceId;
    private WebSocketStompClient stompClient;
    private StompSession stompSession;

    @BeforeEach
    void setUp() {
        workspaceId = prototypeProperties.workspaceId();
        devToken = TestJwtUtil.generateToken(jwtSecret, devProperties.userId());

        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");

        rateLimiter.resetAll();

        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
        stompClient.stop();
        stompSession = null;
    }

    // --- helpers ---

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private GeoJsonGeometry squarePolygon() { return TestFixtures.squarePolygon(); }
    private GeoJsonGeometry altPolygon() { return TestFixtures.altPolygon(); }
    private GeoJsonGeometry selfIntersectingPolygon() { return TestFixtures.selfIntersectingPolygon(); }

    private String areasUrl() {
        return "/workspaces/" + workspaceId + "/areas";
    }

    private UUID createArea() {
        ResponseEntity<AreaResponse> r = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Area", squarePolygon()), auth(devToken)),
                AreaResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().id();
    }

    private double committedCount(String changeType) {
        Counter c = meterRegistry.find("area.mutations.committed.total")
                .tag("change_type", changeType).counter();
        return c == null ? 0.0 : c.count();
    }

    private double versionConflictCount() {
        Counter c = meterRegistry.find("area.version.conflict.total").counter();
        return c == null ? 0.0 : c.count();
    }

    private double rateLimitRejectedCount() {
        Counter c = meterRegistry.find("drawing.rate.limit.rejected.total").counter();
        return c == null ? 0.0 : c.count();
    }

    private StompSession connectWithToken(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        stompSession = stompClient
                .connectAsync(URI.create("ws://localhost:" + port + "/ws"),
                        new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        return stompSession;
    }

    private void awaitGaugeValue(int expected, int timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (wsMetrics.getActiveSessionCount() == expected) return;
            Thread.sleep(50);
        }
        assertThat(wsMetrics.getActiveSessionCount())
                .as("websocket.connections.active")
                .isEqualTo(expected);
    }

    // --- TC-METRICS-001 ---

    @Test
    void createArea_incrementsCommittedMutationCounter() {
        double before = committedCount("CREATED");
        createArea();
        assertThat(committedCount("CREATED") - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-002 ---

    @Test
    void updateArea_incrementsCommittedMutationCounter() {
        UUID id = createArea();
        double before = committedCount("UPDATED");
        restTemplate.exchange(
                areasUrl() + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Updated", altPolygon()), auth(devToken)),
                AreaResponse.class);
        assertThat(committedCount("UPDATED") - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-003 ---

    @Test
    void deleteArea_incrementsCommittedMutationCounter() {
        UUID id = createArea();
        double before = committedCount("DELETED");
        restTemplate.exchange(
                areasUrl() + "/" + id + "?expectedVersion=1",
                HttpMethod.DELETE,
                new HttpEntity<>(null, auth(devToken)),
                AreaResponse.class);
        assertThat(committedCount("DELETED") - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-004 ---

    @Test
    void restoreArea_incrementsCommittedMutationCounter() {
        UUID id = createArea();
        restTemplate.exchange(
                areasUrl() + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Updated", altPolygon()), auth(devToken)),
                AreaResponse.class);
        double before = committedCount("RESTORED");
        restTemplate.exchange(
                areasUrl() + "/" + id + "/versions/1/restore",
                HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(2), auth(devToken)),
                AreaResponse.class);
        assertThat(committedCount("RESTORED") - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-005 ---

    @Test
    void versionConflict_incrementsVersionConflictCounter() {
        UUID id = createArea();
        double before = versionConflictCount();
        // PUT with stale expectedVersion=0 → VERSION_CONFLICT
        ResponseEntity<Map> r = restTemplate.exchange(
                areasUrl() + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(0, "Stale", altPolygon()), auth(devToken)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("VERSION_CONFLICT");
        assertThat(versionConflictCount() - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-006 ---

    @Test
    void rateLimitExceeded_incrementsRateLimitRejectedCounter() {
        // Pre-exhaust the per-user quota (default 50) via the in-memory counter
        // to avoid making 50 HTTP requests.
        UUID userId = devProperties.userId();
        for (int i = 0; i < 50; i++) {
            rateLimiter.tryConsume(userId);
        }

        double before = rateLimitRejectedCount();
        ResponseEntity<Map> r = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Rate", squarePolygon()), auth(devToken)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimitRejectedCount() - before).isEqualTo(1.0);
    }

    // --- TC-METRICS-007 ---

    @Test
    void failedMutation_doesNotIncrementCommittedCounter() {
        double before = committedCount("CREATED");
        ResponseEntity<Map> r = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Bad", selfIntersectingPolygon()), auth(devToken)),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_GEOMETRY");
        assertThat(committedCount("CREATED")).isEqualTo(before);
    }

    // --- TC-METRICS-008 ---

    @Test
    void actuatorPrometheus_isAccessibleAndContainsExpectedMetrics() {
        // Pre-register: counters are initialized in AreaMetricsListener constructor,
        // so they appear in Prometheus output even with 0 value.
        ResponseEntity<String> r = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/plain");
        // Micrometer converts dots to underscores for Prometheus metric names
        assertThat(r.getBody()).contains("area_mutations_committed_total");
        assertThat(r.getBody()).contains("change_type=\"CREATED\"");
        assertThat(r.getBody()).contains("area_version_conflict_total");
        assertThat(r.getBody()).contains("drawing_rate_limit_rejected_total");
        assertThat(r.getBody()).contains("websocket_connections_active");
    }

    // --- TC-METRICS-009 ---

    @Test
    void authenticatedStompConnect_incrementsActiveSessionGauge() throws Exception {
        int before = wsMetrics.getActiveSessionCount();
        connectWithToken(devToken);
        awaitGaugeValue(before + 1, 2000);
    }

    // --- TC-METRICS-010 ---

    @Test
    void stompDisconnect_decrementsActiveSessionGauge() throws Exception {
        int before = wsMetrics.getActiveSessionCount();
        connectWithToken(devToken);
        awaitGaugeValue(before + 1, 2000);

        stompSession.disconnect();
        stompClient.stop();
        stompSession = null;  // prevent tearDown from disconnecting again

        awaitGaugeValue(before, 2000);
    }

    // --- TC-METRICS-011 ---

    @Test
    void rejectedStompConnect_doesNotIncrementActiveSessionGauge() {
        int before = wsMetrics.getActiveSessionCount();
        String badToken = TestJwtUtil.generateToken(
                "wrong-secret-for-invalid-signature-xxxx0000000000000000000000", devProperties.userId());
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + badToken);

        assertThatThrownBy(() ->
                stompClient.connectAsync(URI.create("ws://localhost:" + port + "/ws"),
                        new WebSocketHttpHeaders(), headers,
                        new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        assertThat(wsMetrics.getActiveSessionCount()).isEqualTo(before);
    }

    // --- TC-METRICS-012 ---

    @Test
    void duplicateDisconnectEvent_doesNotMakeGaugeNegative() throws Exception {
        int initial = wsMetrics.getActiveSessionCount();
        connectWithToken(devToken);
        awaitGaugeValue(initial + 1, 2000);

        String sessionId = stompSession.getSessionId();

        stompSession.disconnect();
        stompClient.stop();
        stompSession = null;

        awaitGaugeValue(initial, 2000);

        // Simulate a duplicate disconnect event for the same session (already removed).
        // ConcurrentHashMap.newKeySet().remove() is idempotent; gauge must stay at initial.
        wsMetrics.getActiveSessions().remove(sessionId);

        assertThat(wsMetrics.getActiveSessionCount()).isEqualTo(initial);
        assertThat(wsMetrics.getActiveSessionCount()).isGreaterThanOrEqualTo(0);
    }
}
