package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.security.CookieSigner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeControllerIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
;

    @LocalServerPort
    private int port;

    @Autowired
    private DatabaseClient db;
    @Autowired
    private CookieSigner cookieSigner;
    @Autowired
    private IdentityAssertionSigner assertionSigner;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
            () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("management.server.port", () -> "0");
        registry.add("identity.legacy.session.secret", () -> "test-session-secret-32-chars-min!!");
        // Slice 2K：启用断言消费，验证 /api/auth/me 经 CurrentAccountResolver 信任断言头。
        registry.add("identity-assertion.enabled", () -> "true");
        registry.add("identity-assertion.secret", () -> "test-assertion-secret-32-chars!!");
        registry.add("identity-assertion.audience", () -> "grassland-internal");
    }

    @BeforeAll
    static void initSchema() throws Exception {
        try (var conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            try (InputStream in = MeControllerIT.class.getResourceAsStream("/legacy-schema.sql")) {
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String s : sql.split(";")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void returnsCurrentUserWhenSessionValid() {
        String userId = seedUser("active", "active@example.com");
        seedSession("sid-active", userId);
        client().get().uri("/api/auth/me")
            .header(HttpHeaders.COOKIE, "y1.sid=" + cookieSigner.signCookie("sid-active"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.user.id").isEqualTo(userId)
            .jsonPath("$.data.user.email").isEqualTo("active@example.com")
            .jsonPath("$.data.user.role").isEqualTo("user")
            .jsonPath("$.data.user.displayName").isEqualTo("测试用户");
    }

    @Test
    void returnsUserFromAssertionWithoutCookie() {
        // Slice 2K：/api/auth/me 经 CurrentAccountResolver 信任 X-Grassland-Identity 断言（无 cookie）。
        String userId = seedUser("active", "assertme@example.com");
        client().get().uri("/api/auth/me")
            .header("X-Grassland-Identity", signAssertion(userId))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.user.id").isEqualTo(userId)
            .jsonPath("$.data.user.email").isEqualTo("assertme@example.com");
    }

    @Test
    void unauthorizedWithoutCookie() {
        client().get().uri("/api/auth/me")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("请先登录");
    }

    @Test
    void unauthorizedWhenSessionMissing() {
        client().get().uri("/api/auth/me")
            .header(HttpHeaders.COOKIE, "y1.sid=" + cookieSigner.signCookie("unknown-sid"))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("请先登录");
    }

    @Test
    void unauthorizedWhenUserDeleted() {
        seedSession("sid-orphan", "00000000-0000-0000-0000-000000000000");
        client().get().uri("/api/auth/me")
            .header(HttpHeaders.COOKIE, "y1.sid=" + cookieSigner.signCookie("sid-orphan"))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("用户不存在");
    }

    @Test
    void forbiddenWhenUserInactive() {
        String userId = seedUser("suspended", "suspended@example.com");
        seedSession("sid-inactive", userId);
        client().get().uri("/api/auth/me")
            .header(HttpHeaders.COOKIE, "y1.sid=" + cookieSigner.signCookie("sid-inactive"))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.error").isEqualTo("当前账号不可用");
    }

    @Test
    void unauthorizedWhenSessionExpired() {
        String userId = seedUser("active", "expired@example.com");
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() - interval '1 hour')")
            .bind("sid", "sid-expired").bind("sess", sessionJson(userId))
            .then().block();
        client().get().uri("/api/auth/me")
            .header(HttpHeaders.COOKIE, "y1.sid=" + cookieSigner.signCookie("sid-expired"))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    private String signAssertion(String accountId) {
        Instant now = Instant.now();
        return assertionSigner.sign(new IdentityAssertion(
                accountId, null, "sid-me", null, null, "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60)));
    }

    private String seedUser(String status, String email) {
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                + "VALUES (CAST(:id AS uuid), :email, 'hash', '测试用户', 'user', :status)")
            .bind("id", id).bind("email", email).bind("status", status)
            .then().block();
        return id;
    }

    private void seedSession(String sid, String userId) {
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '1 hour')")
            .bind("sid", sid).bind("sess", sessionJson(userId))
            .then().block();
    }

    private String sessionJson(String userId) {
        return "{\"user\":{\"id\":\"" + userId + "\",\"email\":\"user@example.com\","
            + "\"displayName\":\"测试用户\",\"role\":\"user\"},\"cookie\":{}}";
    }
}
