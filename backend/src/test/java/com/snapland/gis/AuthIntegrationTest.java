package com.snapland.gis;

import com.snapland.gis.auth.dto.AuthResponse;
import com.snapland.gis.auth.dto.LoginRequest;
import com.snapland.gis.auth.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.auth.jwt-secret}")
    private String jwtSecret;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.update(
            "DELETE FROM workspace_member WHERE user_id NOT IN (SELECT id FROM users WHERE email = 'test@snapland.local')");
        jdbcTemplate.update("DELETE FROM users WHERE email != 'test@snapland.local'");
    }

    // --- TC-AUTH-REG-001: successful registration ---

    @Test
    void register_validRequest_returns201WithTokenAndUser() {
        var request = new RegisterRequest("Alice", "alice@example.com");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/register", request, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.token()).isNotBlank();
        assertThat(body.user()).isNotNull();
        assertThat(body.user().id()).isNotNull();
        assertThat(body.user().name()).isEqualTo("Alice");
        assertThat(body.user().email()).isEqualTo("alice@example.com");
    }

    // --- TC-AUTH-REG-002: email normalised to lowercase ---

    @Test
    void register_emailNormalisedToLowercase() {
        var request = new RegisterRequest("Bob", "BOB@EXAMPLE.COM");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/register", request, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().user().email()).isEqualTo("bob@example.com");
    }

    // --- TC-AUTH-REG-003: duplicate email returns 409 ---

    @Test
    void register_duplicateEmail_returns409() {
        var request = new RegisterRequest("Alice", "alice@example.com");
        restTemplate.postForEntity("/auth/register", request, AuthResponse.class);

        ResponseEntity<Map> second = restTemplate.postForEntity(
                "/auth/register", request, Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsKey("code");
        assertThat(second.getBody().get("code")).isEqualTo("USER_ALREADY_EXISTS");
    }

    // --- TC-AUTH-REG-004: token is verifiable and contains correct subject ---

    @Test
    void register_tokenContainsUserIdAsSubject() {
        var request = new RegisterRequest("Carol", "carol@example.com");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/register", request, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthResponse body = response.getBody();
        String subject = TestJwtUtil.extractSubject(jwtSecret, body.token());
        assertThat(subject).isEqualTo(body.user().id().toString());
    }

    // --- TC-AUTH-REG-005: registration does not create workspace membership ---

    @Test
    void register_doesNotCreateWorkspaceMembership() {
        var request = new RegisterRequest("Dave", "dave@example.com");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/register", request, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var userId = response.getBody().user().id();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE user_id = ?::uuid",
                Integer.class, userId.toString());
        assertThat(count).isEqualTo(0);
    }

    // --- TC-AUTH-REG-006: blank name returns 400 ---

    @Test
    void register_blankName_returns400() {
        var request = new RegisterRequest("", "eve@example.com");
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- TC-AUTH-REG-007: invalid email returns 400 ---

    @Test
    void register_invalidEmail_returns400() {
        var request = new RegisterRequest("Frank", "not-an-email");
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- TC-AUTH-LOGIN-001: successful login returns token ---

    @Test
    void login_existingUser_returns200WithToken() {
        restTemplate.postForEntity("/auth/register",
                new RegisterRequest("Grace", "grace@example.com"), AuthResponse.class);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("grace@example.com"), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse body = response.getBody();
        assertThat(body.token()).isNotBlank();
        assertThat(body.user().email()).isEqualTo("grace@example.com");
    }

    // --- TC-AUTH-LOGIN-002: login email normalised ---

    @Test
    void login_emailNormalisedToLowercase() {
        restTemplate.postForEntity("/auth/register",
                new RegisterRequest("Hank", "hank@example.com"), AuthResponse.class);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("HANK@EXAMPLE.COM"), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().user().email()).isEqualTo("hank@example.com");
    }

    // --- TC-AUTH-LOGIN-003: unknown email returns 404 ---

    @Test
    void login_unknownEmail_returns404() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("nobody@example.com"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("USER_NOT_FOUND");
    }

    // --- TC-AUTH-MULTI-001: two users can independently register and login ---

    @Test
    void twoUsers_independentRegistrationAndLogin() {
        ResponseEntity<AuthResponse> aliceReg = restTemplate.postForEntity(
                "/auth/register", new RegisterRequest("Alice", "alice2@example.com"), AuthResponse.class);
        ResponseEntity<AuthResponse> bobReg = restTemplate.postForEntity(
                "/auth/register", new RegisterRequest("Bob", "bob2@example.com"), AuthResponse.class);

        assertThat(aliceReg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bobReg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(aliceReg.getBody().user().id()).isNotEqualTo(bobReg.getBody().user().id());

        ResponseEntity<AuthResponse> aliceLogin = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("alice2@example.com"), AuthResponse.class);
        ResponseEntity<AuthResponse> bobLogin = restTemplate.postForEntity(
                "/auth/login", new LoginRequest("bob2@example.com"), AuthResponse.class);

        assertThat(aliceLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceLogin.getBody().user().id()).isEqualTo(aliceReg.getBody().user().id());
        assertThat(bobLogin.getBody().user().id()).isEqualTo(bobReg.getBody().user().id());
    }
}
