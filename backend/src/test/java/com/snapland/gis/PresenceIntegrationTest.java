package com.snapland.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.realtime.PresenceEventMessage;
import com.snapland.gis.realtime.PresenceService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Active Users / Presence feature.
 *
 * TC-PRESENCE-001  Unauthenticated GET /presence → 401
 * TC-PRESENCE-002  Unknown workspaceId GET /presence → 404 WORKSPACE_NOT_FOUND
 * TC-PRESENCE-003  Authenticated GET with no active sessions → 200 []
 * TC-PRESENCE-004  After STOMP connect → GET includes user with correct name
 * TC-PRESENCE-005  STOMP connect → PRESENCE_JOINED broadcast on presence topic
 * TC-PRESENCE-006  STOMP disconnect → PRESENCE_LEFT broadcast on presence topic
 * TC-PRESENCE-007  After STOMP disconnect → GET returns empty list
 * TC-PRESENCE-008  Two sessions for same user → only one PRESENCE_JOINED; first disconnect has no PRESENCE_LEFT
 * TC-PRESENCE-009  Last session for multi-tab user disconnects → PRESENCE_LEFT broadcast
 * TC-PRESENCE-010  Two distinct users → correct PRESENCE_JOINED events and snapshot count
 * TC-PRESENCE-011  PRESENCE_JOINED payload contains correct userId and display name
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PresenceIntegrationTest {

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

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private DevProperties devProperties;
    @Autowired private PrototypeProperties prototypeProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PresenceService presenceService;
    @Value("${app.auth.jwt-secret}") private String jwtSecret;

    private UUID workspaceId;
    private String devToken;
    private UUID secondUserId;
    private String secondToken;

    private WebSocketStompClient stompClient;
    private WebSocketStompClient stompClient2;
    private StompSession session1;
    private StompSession session2;

    @BeforeEach
    void setUp() {
        workspaceId = prototypeProperties.workspaceId();
        devToken = TestJwtUtil.generateToken(jwtSecret, devProperties.userId());

        secondUserId = UUID.randomUUID();
        secondToken = TestJwtUtil.generateToken(jwtSecret, secondUserId);
        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, created_at)
                VALUES (?::uuid, ?, ?, NOW())
                ON CONFLICT (id) DO NOTHING
                """, secondUserId.toString(), "Second User", "second-" + secondUserId + "@snapland.local");

        stompClient = newStompClient();
        stompClient2 = newStompClient();

        awaitEmptyPresence();
    }

    @AfterEach
    void tearDown() {
        if (session1 != null && session1.isConnected()) session1.disconnect();
        if (session2 != null && session2.isConnected()) session2.disconnect();
        stompClient.stop();
        stompClient2.stop();
        session1 = null;
        session2 = null;
    }

    // --- helpers ---

    private WebSocketStompClient newStompClient() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(converter);
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync(
                URI.create("ws://localhost:" + port + "/ws"),
                new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<PresenceEventMessage> subscribeToPresence(StompSession session)
            throws InterruptedException {
        BlockingQueue<PresenceEventMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/workspace/" + workspaceId + "/presence", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return PresenceEventMessage.class; }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((PresenceEventMessage) payload);
            }
        });
        Thread.sleep(200);
        return queue;
    }

    private void awaitEmptyPresence() {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (presenceService.getActiveUsers().isEmpty()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        assertThat(presenceService.getActiveUsers())
                .as("Presence not empty before test started").isEmpty();
    }

    private void awaitPresenceSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (presenceService.getActiveUsers().size() == expected) return;
            Thread.sleep(50);
        }
        assertThat(presenceService.getActiveUsers())
                .as("Presence size").hasSize(expected);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String presenceUrl() {
        return "/workspaces/" + workspaceId + "/presence";
    }

    // --- TC-PRESENCE-001 ---

    @Test
    void unauthenticated_getPresence_returns401() {
        ResponseEntity<Map> r = restTemplate.getForEntity(presenceUrl(), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- TC-PRESENCE-002 ---

    @Test
    void unknownWorkspace_getPresence_returns404() {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<Map> r = restTemplate.exchange(
                "/workspaces/" + unknownId + "/presence", HttpMethod.GET,
                new HttpEntity<>(auth(devToken)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code")).isEqualTo("WORKSPACE_NOT_FOUND");
    }

    // --- TC-PRESENCE-003 ---

    @Test
    void noActiveSessions_getPresence_returnsEmptyList() {
        ResponseEntity<List> r = restTemplate.exchange(
                presenceUrl(), HttpMethod.GET,
                new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEmpty();
    }

    // --- TC-PRESENCE-004 ---

    @Test
    void afterConnect_getPresence_includesUserWithName() throws Exception {
        session1 = connect(stompClient, devToken);
        awaitPresenceSize(1);

        ResponseEntity<List> r = restTemplate.exchange(
                presenceUrl(), HttpMethod.GET,
                new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        Map<?, ?> user = (Map<?, ?>) r.getBody().get(0);
        assertThat(user.get("id")).isEqualTo(devProperties.userId().toString());
        assertThat(user.get("name")).isEqualTo("Test User");
    }

    // --- TC-PRESENCE-005 ---

    @Test
    void connect_broadcastsPresenceJoinedEvent() throws Exception {
        session1 = connect(stompClient, devToken);
        BlockingQueue<PresenceEventMessage> events = subscribeToPresence(session1);

        session2 = connect(stompClient2, secondToken);

        PresenceEventMessage event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("PRESENCE_JOINED");
        assertThat(event.user().id()).isEqualTo(secondUserId.toString());
        assertThat(event.user().name()).isEqualTo("Second User");
    }

    // --- TC-PRESENCE-006 ---

    @Test
    void disconnect_broadcastsPresenceLeftEvent() throws Exception {
        session2 = connect(stompClient2, secondToken);
        awaitPresenceSize(1);

        session1 = connect(stompClient, devToken);
        BlockingQueue<PresenceEventMessage> events = subscribeToPresence(session1);

        session2.disconnect();
        stompClient2.stop();
        session2 = null;

        PresenceEventMessage event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("PRESENCE_LEFT");
        assertThat(event.user().id()).isEqualTo(secondUserId.toString());
        assertThat(event.user().name()).isEqualTo("Second User");
    }

    // --- TC-PRESENCE-007 ---

    @Test
    void afterDisconnect_getPresence_excludesUser() throws Exception {
        session1 = connect(stompClient, devToken);
        awaitPresenceSize(1);

        session1.disconnect();
        stompClient.stop();
        session1 = null;

        awaitPresenceSize(0);

        ResponseEntity<List> r = restTemplate.exchange(
                presenceUrl(), HttpMethod.GET,
                new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEmpty();
    }

    // --- TC-PRESENCE-008 ---

    @Test
    void multiTab_firstSessionDisconnect_doesNotBroadcastPresenceLeft() throws Exception {
        session1 = connect(stompClient, devToken);
        BlockingQueue<PresenceEventMessage> events = subscribeToPresence(session1);

        // first tab of second user
        session2 = connect(stompClient2, secondToken);
        PresenceEventMessage joined = events.poll(5, TimeUnit.SECONDS);
        assertThat(joined).isNotNull();
        assertThat(joined.type()).isEqualTo("PRESENCE_JOINED");

        // second tab of second user
        WebSocketStompClient thirdClient = newStompClient();
        StompSession session3 = null;
        try {
            session3 = connect(thirdClient, secondToken);
            awaitPresenceSize(2); // devUser + secondUser (counted once regardless of tab count)

            // disconnect first tab
            session2.disconnect();
            session2 = null;

            PresenceEventMessage noEvent = events.poll(600, TimeUnit.MILLISECONDS);
            assertThat(noEvent).as("No PRESENCE_LEFT while second tab is still open").isNull();
        } finally {
            if (session3 != null && session3.isConnected()) session3.disconnect();
            thirdClient.stop();
        }
    }

    // --- TC-PRESENCE-009 ---

    @Test
    void multiTab_lastSessionDisconnect_broadcastsPresenceLeft() throws Exception {
        // second user opens two tabs
        session2 = connect(stompClient2, secondToken);
        WebSocketStompClient thirdClient = newStompClient();
        StompSession session3 = null;
        try {
            session3 = connect(thirdClient, secondToken);
            awaitPresenceSize(1); // secondUser counted once

            session1 = connect(stompClient, devToken);
            awaitPresenceSize(2);
            BlockingQueue<PresenceEventMessage> events = subscribeToPresence(session1);

            // close first tab — no PRESENCE_LEFT
            session2.disconnect();
            session2 = null;
            assertThat(events.poll(400, TimeUnit.MILLISECONDS))
                    .as("No PRESENCE_LEFT after first tab close").isNull();

            // close last tab — PRESENCE_LEFT expected
            session3.disconnect();
            thirdClient.stop();
            session3 = null;

            PresenceEventMessage event = events.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo("PRESENCE_LEFT");
            assertThat(event.user().id()).isEqualTo(secondUserId.toString());
        } finally {
            if (session3 != null && session3.isConnected()) session3.disconnect();
            thirdClient.stop();
        }
    }

    // --- TC-PRESENCE-010 ---

    @Test
    void twoDistinctUsers_snapshotIncludesBoth() throws Exception {
        session1 = connect(stompClient, devToken);
        session2 = connect(stompClient2, secondToken);
        awaitPresenceSize(2);

        ResponseEntity<List> r = restTemplate.exchange(
                presenceUrl(), HttpMethod.GET,
                new HttpEntity<>(auth(devToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(2);

        List<String> ids = r.getBody().stream()
                .map(o -> (String) ((Map<?, ?>) o).get("id"))
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(
                devProperties.userId().toString(), secondUserId.toString());
    }

    // --- TC-PRESENCE-011 ---

    @Test
    void presenceJoinedEvent_containsCorrectIdAndName() throws Exception {
        session1 = connect(stompClient, devToken);
        BlockingQueue<PresenceEventMessage> events = subscribeToPresence(session1);

        session2 = connect(stompClient2, secondToken);

        PresenceEventMessage event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("PRESENCE_JOINED");
        assertThat(event.user().id()).isEqualTo(secondUserId.toString());
        assertThat(event.user().name()).isEqualTo("Second User");
    }
}
