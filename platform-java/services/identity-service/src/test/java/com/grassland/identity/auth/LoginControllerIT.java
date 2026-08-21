package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginControllerIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort private int port;
    @Autowired private DatabaseClient db;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName());
        r.add("spring.r2dbc.username", postgres::getUsername);
        r.add("spring.r2dbc.password", postgres::getPassword);
        r.add("management.server.port", () -> "0");
        r.add("identity.session.secret", () -> "test-secret-32-chars-minimum!!!");
    }

    @BeforeAll
    static void schema() throws Exception {
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE app_users (id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL, display_name text, role text NOT NULL DEFAULT 'user', status text NOT NULL DEFAULT 'active', created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), last_login_at timestamptz)");
            s.execute("CREATE TABLE session (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL)");
        }
    }

    private WebTestClient client() {
        // responseTimeout 30s：默认 5s 在 CI 慢 runner 负载下偶发超时（与 IdentityItSupport/FinanceItSupport 同口径）
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(30)).build();
    }

    @Test
    void loginSuccessReturnsUserAndSetCookie() {
        String id = seedUser("active@example.com", "correct-pass", "active");
        client().post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"email\":\"active@example.com\",\"password\":\"correct-pass\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.user.id").isEqualTo(id)
            .jsonPath("$.data.user.email").isEqualTo("active@example.com")
            .consumeWith(r -> assertThat(r.getResponseHeaders().getFirst("Set-Cookie")).contains("y1.sid=").contains("HttpOnly").contains("SameSite=Lax"));
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        seedUser("wrong@example.com", "correct-pass", "active");
        client().post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"email\":\"wrong@example.com\",\"password\":\"bad-pass\"}")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("\u90ae\u7bb1\u6216\u5bc6\u7801\u9519\u8bef");
    }

    @Test
    void loginWithUnknownEmailReturns401SameMessage() {
        client().post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"email\":\"nobody@example.com\",\"password\":\"anything\"}")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("\u90ae\u7bb1\u6216\u5bc6\u7801\u9519\u8bef");
    }

    @Test
    void loginInactiveUserReturns401() {
        seedUser("inactive@example.com", "correct-pass", "suspended");
        client().post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"email\":\"inactive@example.com\",\"password\":\"correct-pass\"}")
            .exchange().expectStatus().isUnauthorized();
    }

    private String seedUser(String email, String password, String status) {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray());
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) VALUES (CAST(:id AS uuid), :email, :hash, 'Test User', 'user', :status)")
            .bind("id", id).bind("email", email).bind("hash", hash).bind("status", status)
            .then().block();
        return id;
    }
}
