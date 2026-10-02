package com.snapland.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.area.dto.RestoreAreaRequest;
import com.snapland.gis.area.dto.UpdateAreaRequest;
import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.realtime.AreaEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
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
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
class AreaWebSocketIntegrationTest {

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

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DevProperties devProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID workspaceId;
    private WebSocketStompClient stompClient;
    private StompSession stompSession;

    @BeforeEach
    void setUp() {
        workspaceId = devProperties.workspaceId();
        jdbcTemplate.update("DELETE FROM area_version");
        jdbcTemplate.update("DELETE FROM area");

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
    }

    // --- helpers ---

    private StompSession connect() throws Exception {
        String url = "ws://localhost:" + port + "/ws";
        stompSession = stompClient
                .connectAsync(url, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        return stompSession;
    }

    private BlockingQueue<AreaEventMessage> subscribeToWorkspace(StompSession session) {
        return subscribeToWorkspace(session, workspaceId);
    }

    private BlockingQueue<AreaEventMessage> subscribeToWorkspace(StompSession session, UUID wsId) {
        BlockingQueue<AreaEventMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/workspace/" + wsId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return AreaEventMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((AreaEventMessage) payload);
            }
        });
        return queue;
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

    private UUID createArea() {
        ResponseEntity<AreaResponse> response = restTemplate.postForEntity(
                areasUrl(), new CreateAreaRequest("Test", squarePolygon()), AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    // --- tests ---

    @Test
    void websocket_connectionSucceeds() throws Exception {
        StompSession session = connect();
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void createArea_publishesAreaCreatedEvent() throws Exception {
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        UUID areaId = createArea();

        AreaEventMessage event = queue.poll(5, TimeUnit.SECONDS);

        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("AREA_CREATED");
        assertThat(event.workspaceId()).isEqualTo(workspaceId.toString());
        assertThat(event.timestamp()).isNotBlank();
        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().id()).isEqualTo(areaId);
        assertThat(event.payload().currentVersion()).isEqualTo(1);
        assertThat(event.payload().status()).isEqualTo("ACTIVE");
    }

    @Test
    void updateArea_publishesAreaUpdatedEventWithNewVersion() throws Exception {
        UUID areaId = createArea();
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        restTemplate.exchange(
                areasUrl() + "/" + areaId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(1, "Updated", squarePolygon())),
                AreaResponse.class);

        AreaEventMessage event = queue.poll(5, TimeUnit.SECONDS);

        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("AREA_UPDATED");
        assertThat(event.workspaceId()).isEqualTo(workspaceId.toString());
        assertThat(event.timestamp()).isNotBlank();
        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().id()).isEqualTo(areaId);
        assertThat(event.payload().currentVersion()).isEqualTo(2);
        assertThat(event.payload().status()).isEqualTo("ACTIVE");
    }

    @Test
    void deleteArea_publishesAreaDeletedEvent() throws Exception {
        UUID areaId = createArea();
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        restTemplate.exchange(
                areasUrl() + "/" + areaId + "?expectedVersion=1",
                HttpMethod.DELETE, null, AreaResponse.class);

        AreaEventMessage event = queue.poll(5, TimeUnit.SECONDS);

        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("AREA_DELETED");
        assertThat(event.workspaceId()).isEqualTo(workspaceId.toString());
        assertThat(event.timestamp()).isNotBlank();
        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().id()).isEqualTo(areaId);
        assertThat(event.payload().currentVersion()).isEqualTo(2);
        assertThat(event.payload().status()).isEqualTo("DELETED");
    }

    @Test
    void restoreArea_publishesAreaRestoredEventWithNewCurrentVersion() throws Exception {
        UUID areaId = createArea();
        // delete to version 2, then restore from version 1
        restTemplate.exchange(
                areasUrl() + "/" + areaId + "?expectedVersion=1",
                HttpMethod.DELETE, null, AreaResponse.class);

        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        restTemplate.exchange(
                areasUrl() + "/" + areaId + "/versions/1/restore",
                HttpMethod.POST,
                new HttpEntity<>(new RestoreAreaRequest(2)),
                AreaResponse.class);

        AreaEventMessage event = queue.poll(5, TimeUnit.SECONDS);

        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("AREA_RESTORED");
        assertThat(event.workspaceId()).isEqualTo(workspaceId.toString());
        assertThat(event.timestamp()).isNotBlank();
        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().id()).isEqualTo(areaId);
        assertThat(event.payload().currentVersion()).isEqualTo(3);
        assertThat(event.payload().status()).isEqualTo("ACTIVE");
    }

    @Test
    void workspaceIsolation_otherWorkspaceEventNotReceived() throws Exception {
        UUID otherWorkspaceId = UUID.randomUUID();
        UUID userId = devProperties.userId();
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_by) VALUES (?::uuid, 'Other', ?::uuid)",
                otherWorkspaceId.toString(), userId.toString());

        // subscribe to the dev workspace topic only
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        // trigger a mutation in the other workspace
        restTemplate.postForEntity(
                "/workspaces/" + otherWorkspaceId + "/areas",
                new CreateAreaRequest("Other Area", squarePolygon()),
                AreaResponse.class);

        // assert no event arrives on the dev workspace topic
        AreaEventMessage event = queue.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNull();
    }

    @Test
    void invalidGeometry_producesNoEvent() throws Exception {
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        // bowtie polygon — invalid, rejected before any DB write
        List<List<Double>> ring = List.of(
                List.of(0.0, 0.0), List.of(2.0, 2.0), List.of(2.0, 0.0),
                List.of(0.0, 2.0), List.of(0.0, 0.0));
        restTemplate.postForEntity(
                areasUrl(),
                new CreateAreaRequest("Bad", new GeoJsonGeometry("Polygon", List.of(ring))),
                Object.class);

        AreaEventMessage event = queue.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNull();
    }

    @Test
    void versionConflict_producesNoEvent() throws Exception {
        UUID areaId = createArea();
        BlockingQueue<AreaEventMessage> queue = subscribeToWorkspace(connect());

        // stale expectedVersion — rejected by OCC, transaction rolled back
        restTemplate.exchange(
                areasUrl() + "/" + areaId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateAreaRequest(99, "Stale", squarePolygon())),
                Object.class);

        AreaEventMessage event = queue.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNull();
    }
}
