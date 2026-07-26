package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

/** SessionIdentityResolver 端到端（testcontainers postgres，无 Spring 上下文，直测 resolver + DatabaseClient）。 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionIdentityResolverIT {

    private static final String SECRET = "test-secret-32-chars-minimum!!!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private DatabaseClient db;
    private SessionIdentityResolver resolver;

    @BeforeAll
    void setUp() throws Exception {
        ConnectionFactory factory = ConnectionFactories.get(r2dbcUrl());
        db = DatabaseClient.create(factory);
        resolver = new SessionIdentityResolver(db, new EdgeCookieSigner(SECRET), "y1.sid");
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE app_users (id uuid PRIMARY KEY, email text UNIQUE, password_hash text, display_name text, role text, status text)");
            s.execute("CREATE TABLE session (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL)");
            // 与 identity V7 对齐：MFA 重认证证明（reauthenticated_at/auth_strength）也由 BFF 读出签进断言
            s.execute("CREATE TABLE identity_session (session_token text PRIMARY KEY, account_id uuid NOT NULL,"
                    + " active_identity_type varchar(32), reauthenticated_at timestamptz,"
                    + " auth_strength varchar(16) NOT NULL DEFAULT 'level1')");
            s.execute("CREATE TABLE organization (id uuid PRIMARY KEY, owner_account_id uuid, name text, status text, permission_tier text, industry text)");
            s.execute("CREATE TABLE identity_profile (id uuid PRIMARY KEY, account_id uuid NOT NULL, identity_type varchar(32) NOT NULL, organization_id uuid, status text, UNIQUE(account_id, identity_type))");
        }
    }

    private String r2dbcUrl() {
        return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
    }

    @Test
    void resolvesAccountAndActiveIdentity() {
        Seeded seeded = seed("merchant@grassland.local", "merchant");
        var request = requestWithCookie(seeded.cookie);

        StepVerifier.create(resolver.resolve(request))
                .assertNext(identity -> {
                    assertThat(identity.accountId()).isEqualTo(seeded.accountId);
                    assertThat(identity.activeIdentityType()).isEqualTo("merchant");
                    assertThat(identity.sessionToken()).isEqualTo(seeded.sid);
                })
                .verifyComplete();
    }

    @Test
    void resolvesOrgAndTierForMerchant() {
        Seeded seeded = seed("org-merchant@grassland.local", "merchant");
        String orgId = UUID.randomUUID().toString();
        db.sql("INSERT INTO organization(id, owner_account_id, name, status, permission_tier) "
                + "VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), 'Org', 'active', 'basic_publish')")
                .bind("id", orgId).bind("owner", seeded.accountId).then().block();
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status) "
                + "VALUES (CAST(:pid AS uuid), CAST(:acct AS uuid), 'merchant', CAST(:org AS uuid), 'active')")
                .bind("pid", UUID.randomUUID().toString()).bind("acct", seeded.accountId).bind("org", orgId).then().block();

        StepVerifier.create(resolver.resolve(requestWithCookie(seeded.cookie)))
                .assertNext(identity -> {
                    assertThat(identity.organizationId()).isEqualTo(orgId);
                    assertThat(identity.permissionTier()).isEqualTo("basic_publish");
                })
                .verifyComplete();
    }

    @Test
    void consumerWhenNoIdentitySessionRow() {
        Seeded seeded = seed("consumer@grassland.local", null);
        var request = requestWithCookie(seeded.cookie);

        StepVerifier.create(resolver.resolve(request))
                .assertNext(identity -> assertThat(identity.activeIdentityType()).isNull())
                .verifyComplete();
    }

    @Test
    void noCookieIsEmpty() {
        StepVerifier.create(resolver.resolve(MockServerHttpRequest.get("/api/auth/me").build()))
                .verifyComplete();
    }

    @Test
    void tamperedCookieIsEmpty() {
        Seeded seeded = seed("tampered@grassland.local", null);
        var request = requestWithCookie(seeded.cookie + "garbage");

        StepVerifier.create(resolver.resolve(request))
                .verifyComplete();
    }

    @Test
    void expiredSessionIsEmpty() {
        String accountId = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, role, status) VALUES (CAST(:id AS uuid), :email, 'x', 'user', 'active')")
                .bind("id", accountId).bind("email", "expired@grassland.local").then().block();
        String sess = "{\"user\":{\"id\":\"" + accountId + "\"}}";
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() - interval '1 day')")
                .bind("sid", sid).bind("sess", sess).then().block();

        StepVerifier.create(resolver.resolve(requestWithCookie(signCookie(sid))))
                .verifyComplete();
    }

    @Test
    void accountMissingIsEmpty() {
        // session 指向一个不存在的账号 → app_users 查询空 → empty。
        String sid = UUID.randomUUID().toString();
        String sess = "{\"user\":{\"id\":\"00000000-0000-0000-0000-000000000000\"}}";
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
                .bind("sid", sid).bind("sess", sess).then().block();

        StepVerifier.create(resolver.resolve(requestWithCookie(signCookie(sid))))
                .verifyComplete();
    }

    /** seed 账号 + 未过期 session（+可选 identity_session 行），返回登录态 cookie。 */
    private Seeded seed(String email, String activeType) {
        String accountId = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, role, status) VALUES (CAST(:id AS uuid), :email, 'x', 'user', 'active')")
                .bind("id", accountId).bind("email", email).then().block();
        String sess = "{\"user\":{\"id\":\"" + accountId + "\"}}";
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
                .bind("sid", sid).bind("sess", sess).then().block();
        if (activeType != null) {
            db.sql("INSERT INTO identity_session(session_token, account_id, active_identity_type) VALUES (:sid, CAST(:acct AS uuid), :type)")
                    .bind("sid", sid).bind("acct", accountId).bind("type", activeType).then().block();
        }
        return new Seeded(signCookie(sid), accountId, sid);
    }

    private static org.springframework.http.server.reactive.ServerHttpRequest requestWithCookie(String cookieValue) {
        return MockServerHttpRequest.get("/api/auth/me")
                .cookie(new HttpCookie("y1.sid", cookieValue))
                .build();
    }

    /** 复刻 identity-service 的 cookie 签名 + URL 编码（edge 端必须能 unsign）。 */
    private static String signCookie(String sid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(sid.getBytes(StandardCharsets.UTF_8));
            String macValue = Base64.getEncoder().encodeToString(digest).replaceAll("=+$", "");
            return URLEncoder.encode("s:" + sid + "." + macValue, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record Seeded(String cookie, String accountId, String sid) {}
}
