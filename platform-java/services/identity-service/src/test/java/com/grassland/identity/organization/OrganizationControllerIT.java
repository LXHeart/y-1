package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.grassland.identity.security.CookieSigner;

/**
 * 端到端验证 Organization（草场身份域 Slice 2E）。
 *
 * <p>{@code from-database-url=true} + {@code DATABASE_URL} 激活 {@code DataSourceConfig} → Flyway 跑 V1 建 organization；
 * {@code @BeforeAll} 手建 app_users/session/outbox（Flyway 不管），seed owner + session 并构造 signed cookie 验证鉴权链路。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private DatabaseClient db;

    @Autowired
    private CookieSigner cookieSigner;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String host = postgres.getHost();
        Integer p = postgres.getMappedPort(5432);
        String name = postgres.getDatabaseName();
        // from-database-url=true 走生产同款路径：R2dbcConnectionFactoryConfig + DataSourceConfig 都从 DATABASE_URL 派生
        // （r2dbc url 含 user/pass；JDBC DataSource 用 setter 设 user/pass）。不设分离的 spring.r2dbc.*。
        r.add("identity.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> "postgresql://" + postgres.getUsername() + ":" + postgres.getPassword()
                + "@" + host + ":" + p + "/" + name);
        r.add("management.server.port", () -> "0");
        r.add("identity.session.secret", () -> "test-secret-32-chars-minimum!!!");
    }

    @BeforeAll
    static void schema() throws Exception {
        // organization 表由 Flyway V1 建；这里补 app_users/session/outbox（Flyway 不管）。
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE app_users (id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL, display_name text, role text NOT NULL DEFAULT 'user', status text NOT NULL DEFAULT 'active', created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), last_login_at timestamptz)");
            s.execute("CREATE TABLE session (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL)");
            s.execute("CREATE TABLE outbox (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), event_id text NOT NULL UNIQUE, event_type text NOT NULL, aggregate_type text NOT NULL, aggregate_id text NOT NULL, payload json NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz)");
        }
    }

    @Test
    void createReturnsCreatedAndPersistsOutboxEvent() {
        String cookie = seedOwnerWithCookie("owner@example.com");
        client().post().uri("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"name\":\"测试商家主体\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.name").isEqualTo("测试商家主体")
                .jsonPath("$.data.status").isEqualTo("active")
                .jsonPath("$.data.ownerAccountId").isNotEmpty();

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox WHERE event_type = 'OrganizationCreated'")
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getByIdAndListByOwner() {
        String cookie = seedOwnerWithCookie("list@example.com");
        String id = createOrg(cookie, "列表主体");

        client().get().uri("/api/organizations/" + id).header("Cookie", "y1.sid=" + cookie).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.id").isEqualTo(id)
                .jsonPath("$.data.name").isEqualTo("列表主体");

        client().get().uri("/api/organizations").header("Cookie", "y1.sid=" + cookie).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(id);
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        client().post().uri("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"无主\"}")
                .exchange()
                .expectStatus().isUnauthorized();
        client().get().uri("/api/organizations").exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @SuppressWarnings("unchecked")
    private String createOrg(String cookie, String name) {
        Map<String, Object> body = client().post().uri("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"name\":\"" + name + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (String) data.get("id");
    }

    /** seed 一个 owner app_users + 其 session，返回可用的 signed cookie value。 */
    private String seedOwnerWithCookie(String email) {
        String ownerId = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                + "VALUES (CAST(:id AS uuid), :email, 'x', 'Org Owner', 'user', 'active')")
                .bind("id", ownerId).bind("email", email).then().block();

        String sid = UUID.randomUUID().toString();
        String sess = "{\"user\":{\"id\":\"" + ownerId + "\"}}";
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
                .bind("sid", sid).bind("sess", sess).then().block();

        return URLEncoder.encode("s:" + cookieSigner.sign(sid), StandardCharsets.UTF_8);
    }
}
