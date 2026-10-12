package com.snapland.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snapland.gis.area.dto.AreaResponse;
import com.snapland.gis.area.dto.CreateAreaRequest;
import com.snapland.gis.area.dto.GeoJsonGeometry;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import com.snapland.gis.realtime.AreaEventMessage;
import com.snapland.gis.realtime.JwtChannelInterceptor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AreaWebSocketAuthIntegrationTest extends IntegrationTestBase {

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
    private JwtChannelInterceptor jwtChannelInterceptor;

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

    private URI wsUri() {
        return URI.create("ws://localhost:" + port + "/ws");
    }

    private StompSession connectWithToken(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        stompSession = stompClient
                .connectAsync(wsUri(), new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        return stompSession;
    }

    private CompletableFuture<StompSession> connectAsyncWithToken(String token) {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync(wsUri(), new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {});
    }

    private CompletableFuture<StompSession> connectAsyncNoAuth() {
        return stompClient.connectAsync(wsUri(), new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() {});
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(devToken);
        return headers;
    }

    private GeoJsonGeometry squarePolygon() { return TestFixtures.squarePolygon(); }

    private UUID createArea() {
        var response = restTemplate.exchange(
                "/workspaces/" + workspaceId + "/areas", HttpMethod.POST,
                new HttpEntity<>(new CreateAreaRequest("Auth Test", squarePolygon()), authHeaders()),
                AreaResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    // --- TC-WS-AUTH-001 ---

    @Test
    void validJwt_connectSucceeds() throws Exception {
        StompSession session = connectWithToken(devToken);
        assertThat(session.isConnected()).isTrue();
    }

    // --- TC-WS-AUTH-002 ---

    @Test
    void missingJwt_connectRejected() {
        CompletableFuture<StompSession> future = connectAsyncNoAuth();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    // --- TC-WS-AUTH-003 ---

    @Test
    void invalidSignature_connectRejected() {
        // Token signed with a different secret — signature will not verify
        String wrongSecret = "wrong-secret-for-invalid-signature-testing-not-for-production-use";
        String badToken = TestJwtUtil.generateToken(wrongSecret, devProperties.userId());
        CompletableFuture<StompSession> future = connectAsyncWithToken(badToken);
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    // --- TC-WS-AUTH-004 ---

    @Test
    void expiredJwt_connectRejected() {
        String expiredToken = TestJwtUtil.generateExpiredToken(jwtSecret, devProperties.userId());
        CompletableFuture<StompSession> future = connectAsyncWithToken(expiredToken);
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    // --- TC-WS-AUTH-005 ---

    @Test
    void authenticatedClient_receivesAreaEvent() throws Exception {
        BlockingQueue<AreaEventMessage> queue = new LinkedBlockingQueue<>();
        StompSession session = connectWithToken(devToken);
        session.subscribe("/topic/workspace/" + workspaceId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return AreaEventMessage.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((AreaEventMessage) payload);
            }
        });

        createArea();

        AreaEventMessage event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.type()).isEqualTo("AREA_CREATED");
    }

    // --- TC-WS-AUTH-006 ---

    @Test
    void authenticatedPrincipal_containsSubjectFromJwt() {
        // Direct interceptor test — no production endpoint required.
        // Verifies that after preSend processes a CONNECT, the accessor's user
        // is a JwtAuthenticationToken whose name equals the JWT `sub` claim.
        UUID userId = devProperties.userId();

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer " + devToken);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor.getUser()).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) resultAccessor.getUser()).getName())
                .isEqualTo(userId.toString());
    }
}
