package com.grassland.identity;

import com.grassland.identity.admin.FinanceCreditsAdminClient;
import com.grassland.identity.security.CookieSigner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.grassland.identity.kyb.KybMediaClient;
import com.grassland.identity.kyb.KybMediaMetadata;
import com.grassland.identity.kyb.KybMediaRetentionReceipt;
import java.time.Instant;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * identity-service 集成测试公共基座。草场身份域 Slice 2F。
 *
 * <p>{@code from-database-url=true} + {@code DATABASE_URL} 激活生产同款 {@code DataSourceConfig} → Flyway 跑 V1+V2
 * 建 organization/store/organization_membership（含 permission_tier）与 outbox；{@code @BeforeAll} 手建 app_users/session。
 * 提供 {@link #seedAccount(String)} 生成登录态 + {@link #createOrg(String, String)} 建组织。
 *
 * <p><b>容器生命周期</b>：采用 testcontainers 单例容器模式——{@link #POSTGRES} 在 static 块启动一次、全程不重启，
 * 端口稳定；多个子类 + Spring 缓存的 ApplicationContext 共享同一容器/同一端口（避免 {@code @Testcontainers} 按类重启
 * 导致缓存上下文持有的旧端口失效、连接被拒）。
 *
 * <p>注意：单例容器在同类内跨 {@code @Test} 共享，数据累积；outbox 计数请按 orgId 限定以解耦顺序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IdentityItSupport {

    @MockitoBean
    protected KybMediaClient kybMediaClient;

    @MockitoBean
    protected FinanceCreditsAdminClient financeCreditsAdminClient;

    /** 共享单例容器：类加载即启动一次，全程不重启 → 端口稳定。 */
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DatabaseClient db;

    @Autowired
    protected CookieSigner cookieSigner;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String host = POSTGRES.getHost();
        Integer p = POSTGRES.getMappedPort(5432);
        String name = POSTGRES.getDatabaseName();
        r.add("identity.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + host + ":" + p + "/" + name);
        r.add("management.server.port", () -> "0");
        r.add("identity.outbox.enabled", () -> "false");
        r.add("identity.kyb.retention.enabled", () -> "false");
        r.add("identity.external-delivery.challenge-secret",
                () -> "test-sms-challenge-secret-at-least-32-characters");
        r.add("identity.legacy.session.secret", () -> "test-secret-32-chars-minimum!!!");
        // Slice 2K：启用内部身份断言消费（signer bean 注入）。仅在请求带断言头时触发，其余走 cookie 路径。
        r.add("identity-assertion.enabled", () -> "true");
        r.add("identity-assertion.secret", () -> "test-assertion-secret-32-chars!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
    }

    @BeforeAll
    static void schema() throws Exception {
        // organization/store/organization_membership/outbox 由 Flyway 建；这里仅补 legacy app_users/session。
        // IF NOT EXISTS：多个子类各自触发一次 @BeforeAll，需幂等。
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS app_users (id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL, display_name text, role text NOT NULL DEFAULT 'user', status text NOT NULL DEFAULT 'active', created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), last_login_at timestamptz)");
            s.execute("CREATE TABLE IF NOT EXISTS session (sid varchar PRIMARY KEY, sess json NOT NULL, expire timestamp(6) NOT NULL)");
            // 注册验证码同属 legacy Express 建的表（identity 只读写，不由 Flyway 建），
            // 列名/类型对齐 EmailVerificationService 的 SQL：明文 code、used 标记。
            s.execute("CREATE TABLE IF NOT EXISTS email_verification_codes (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), "
                    + "email text NOT NULL, code text NOT NULL, used boolean NOT NULL DEFAULT false, "
                    + "expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
        }
    }

    @BeforeEach
    void stubKybMediaValidation() {
        when(financeCreditsAdminClient.award(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(kybMediaClient.retain(any(), any(), any())).thenReturn(Mono.empty());
        when(kybMediaClient.release(any(), any(), any())).thenReturn(Mono.empty());
        when(kybMediaClient.acquireLease(any(), any(), any(), any(), any(Long.class)))
                .thenAnswer(invocation -> Mono.just(new KybMediaRetentionReceipt(
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3),
                        Instant.now().plusSeconds(invocation.getArgument(4)), null)));
        when(kybMediaClient.seal(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Mono.just(new KybMediaRetentionReceipt(
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3),
                        null, invocation.getArgument(4))));
        when(kybMediaClient.requireUsable(any(), any(), any())).thenAnswer(invocation -> {
            UUID mediaId = invocation.getArgument(0);
            String organizationId = invocation.getArgument(1);
            String accountId = invocation.getArgument(2);
            return Mono.just(new KybMediaMetadata(mediaId, accountId, organizationId,
                    "merchant_kyb", "merchant_kyb", organizationId, "active",
                    "image/png", 4096L, null));
        });
    }

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** seed 一个账号（app_users + session），返回 signed cookie value 与 accountId。 */
    protected Seeded seedAccount(String email) {
        String accountId = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                + "VALUES (CAST(:id AS uuid), :email, 'x', 'Test Account', 'user', 'active')")
                .bind("id", accountId).bind("email", email).then().block();
        return new Seeded(signCookie(accountId), accountId);
    }

    /** 用已有 accountId 补一个 session，返回 signed cookie（用于把外部已知账号转成登录态）。 */
    protected String cookieFor(String accountId) {
        return signCookie(accountId);
    }

    /** seed 一个平台管理员账号（role='admin'），返回登录态。草场身份域 Slice 2H（D-05 审核）。 */
    protected Seeded seedAdmin(String email) {
        String accountId = UUID.randomUUID().toString();
        db.sql("""
                WITH inserted_admin AS (
                    INSERT INTO app_users(id, email, password_hash, display_name, role, status)
                    VALUES (CAST(:id AS uuid), :email, 'x', 'Platform Admin', 'admin', 'active')
                    RETURNING id
                )
                INSERT INTO backend_role(account_id, role)
                SELECT id, 'platform_admin' FROM inserted_admin
                """)
                .bind("id", accountId).bind("email", email).then().block();
        return new Seeded(signCookie(accountId), accountId);
    }

    private String signCookie(String accountId) {
        String sid = UUID.randomUUID().toString();
        String sess = "{\"user\":{\"id\":\"" + accountId + "\"}}";
        db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
                .bind("sid", sid).bind("sess", sess).then().block();
        return URLEncoder.encode("s:" + cookieSigner.sign(sid), StandardCharsets.UTF_8);
    }

    /** 用给定 cookie 建一个组织，返回 orgId。 */
    @SuppressWarnings("unchecked")
    protected String createOrg(String cookie, String name) {
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

    /** 用给定 cookie 在 org 下建一个门店，返回 storeId。 */
    @SuppressWarnings("unchecked")
    protected String createStore(String orgId, String cookie, String name) {
        Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/stores")
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

    /** seeded 账号的登录态。 */
    public record Seeded(String cookie, String accountId) {}
}
