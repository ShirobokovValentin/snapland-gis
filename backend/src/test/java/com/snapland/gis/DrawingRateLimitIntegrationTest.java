package com.snapland.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.ratelimit.DrawingRateLimiter;
import com.snapland.gis.realtime.AreaEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.drawing-actions.max-actions=3",
                "app.rate-limit.drawing-actions.window=PT1M"
        })
@Testcontainers
@ActiveProfiles("test")
class DrawingRateLimitIntegrationTest {

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

    // Shared controllable clock — reset to a fixed point in @BeforeEach
    static final TestClock TEST_CLOCK = new TestClock(Instant.parse("2024-01-01T12:00:00Z"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return TEST_CLOCK;
        }
    }

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
    private DrawingRateLimiter rateLimiter;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    private String userAToken;
    private UUID userAId;
    private UUID workspaceId;

    // WebSocket client managed per-test
    private WebSocketStompClient stompClient;
    private StompSession stompSession;

    @BeforeEach
    void setUp() {
        workspaceId = prototypeProperties.workspaceId();
        userAId = devProperties.userId();
        userAToken = TestJwtUtil.generateToken(jwtSecret, userAId);

        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");

        rateLimiter.resetAll();
        TEST_CLOCK.set(Instant.parse("2024-01-01T12:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    // --- helpers ---

    private HttpHeaders authHeaders(String token) {
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

    private GeoJsonGeometry bowtiePolgyon() {
        List<List<Double>> ring = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));
        return new GeoJsonGeometry("Polygon", List.of(ring));
    }

    private String areasUrl() {
        return "/workspaces/" + workspaceId + "/areas";
    }

    private ResponseEntity<AreaResponse> createArea(String token) {
        return restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Area", squarePolygon()), authHeaders(token)),
                AreaResponse.class);
    }

    private ResponseEntity<Map> createAreaForStatus(String token) {
        return restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Area", squarePolygon()), authHeaders(token)),
                Map.class);
    }

    private StompSession connectStomp() throws Exception {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(converter);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + userAToken);
        stompSession = stompClient
                .connectAsync(URI.create("ws://localhost:" + port + "/ws"),
                        new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        return stompSession;
    }

    private BlockingQueue<AreaEventMessage> subscribeToWorkspace(StompSession session) {
        BlockingQueue<AreaEventMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/workspace/" + workspaceId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return AreaEventMessage.class; }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((AreaEventMessage) payload);
            }
        });
        return queue;
    }

    // --- TC-RATE-001: First 3 mutation requests are allowed ---

    @Test
    void firstThreeMutationRequests_areAllAllowed() {
        for (int i = 0; i < 3; i++) {
            assertThat(createArea(userAToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // --- TC-RATE-002: 4th request (limit=3) returns 429 with RATE_LIMIT_EXCEEDED ---

    @Test
    void fourthMutationRequest_returns429WithRateLimitExceeded() {
        for (int i = 0; i < 3; i++) {
            createArea(userAToken);
        }

        ResponseEntity<Map> response = createAreaForStatus(userAToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("code")).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(response.getBody().get("message")).asString().contains("Try again");
        // Clock is fixed: window started at T, remaining = window (60s), Retry-After = 60
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    // --- TC-RATE-003: User B has an independent quota from User A ---

    @Test
    void userB_hasIndependentQuota_afterUserAExhausts() {
        UUID userBId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, created_at) VALUES (?::uuid, 'User B', ?, NOW()) ON CONFLICT (id) DO NOTHING",
                userBId.toString(), "userb-" + userBId + "@test.local");
        String userBToken = TestJwtUtil.generateToken(jwtSecret, userBId);

        // Exhaust User A's quota
        for (int i = 0; i < 3; i++) {
            createArea(userAToken);
        }
        assertThat(createAreaForStatus(userAToken).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // User B has an independent quota and can still create
        assertThat(createArea(userBToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // --- TC-RATE-004: GET requests do not consume drawing quota ---

    @Test
    void getRequests_doNotConsumeDrawingQuota() {
        // Make several GET requests (viewport query)
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    areasUrl() + "?bbox=35.0,32.0,36.0,33.0",
                    HttpMethod.GET,
                    new HttpEntity<>(null, authHeaders(userAToken)),
                    Object.class);
        }

        // Full 3-mutation quota is still available
        for (int i = 0; i < 3; i++) {
            assertThat(createArea(userAToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // Now quota is exhausted
        assertThat(createAreaForStatus(userAToken).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- TC-RATE-005: Unauthenticated request returns 401, not 429; quota is unaffected ---

    @Test
    void unauthenticatedRequest_returns401AndDoesNotConsumeQuota() {
        // Request with no Authorization header
        ResponseEntity<Map> unauth = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Area", squarePolygon())),
                Map.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // User A's full quota is still available
        for (int i = 0; i < 3; i++) {
            assertThat(createArea(userAToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // --- TC-RATE-006: Quota resets after the clock advances past the window ---

    @Test
    void quotaResets_afterClockAdvancesBeyondWindow() {
        // Exhaust quota
        for (int i = 0; i < 3; i++) {
            createArea(userAToken);
        }
        assertThat(createAreaForStatus(userAToken).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Advance clock by more than the 1-minute window — no Thread.sleep
        TEST_CLOCK.advance(Duration.ofMinutes(2));

        // Quota is now available again (new window)
        assertThat(createArea(userAToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // --- TC-RATE-007: 429-rejected request produces no DB write and no WebSocket event ---

    @Test
    void rateLimitedRequest_producesNoDatabaseWriteAndNoWebSocketEvent() throws Exception {
        StompSession session = connectStomp();
        BlockingQueue<AreaEventMessage> events = subscribeToWorkspace(session);

        // Exhaust quota with 3 allowed creates; drain their WebSocket events
        for (int i = 0; i < 3; i++) {
            createArea(userAToken);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(events.poll(5, TimeUnit.SECONDS))
                    .as("Expected WebSocket event for allowed create #" + (i + 1))
                    .isNotNull();
        }

        // Record AreaVersion count before the rate-limited attempt
        long versionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version", Long.class);

        // Attempt a 4th mutation — rate-limited
        assertThat(createAreaForStatus(userAToken).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // No AreaVersion was created
        long versionsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM area_version", Long.class);
        assertThat(versionsAfter).isEqualTo(versionsBefore);

        // No WebSocket event was published
        assertThat(events.poll(2, TimeUnit.SECONDS)).isNull();
    }

    // --- TC-RATE-008: Failed authenticated mutations (400) consume quota ---

    @Test
    void failedAuthenticatedMutations_consumeQuota() {
        // 3 invalid-geometry requests → 400 each, but quota is consumed
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> response = restTemplate.exchange(
                    areasUrl(), HttpMethod.POST,
                    new HttpEntity<>(new CreateAreaRequest("Bad", bowtiePolgyon()), authHeaders(userAToken)),
                    Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // 4th attempt — quota exhausted, returns 429 regardless of payload
        ResponseEntity<Map> response = restTemplate.exchange(
                areasUrl(), HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Any", squarePolygon()), authHeaders(userAToken)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("code")).isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}
