package com.grassland.marketplace;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;
import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/**
 * marketplace 集成测试公共基座。草场 Epic 4 Slice 4A。
 *
 * <p>单例 testcontainers postgres（全程不重启，端口稳定）；{@code marketplace.datasource.from-database-url=true}
 * 触发 {@code MarketplaceDataSourceConfig} → Flyway V1 建 task/outbox；{@code identity-assertion} 注入 signer；
 * 禁用 object-storage（免 MinIO）+ temporal test-server（内存，免 temporal 容器）。提供 {@link #sign} 签断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class MarketplaceItSupport {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected IdentityAssertionSigner signer;

    @Autowired
    protected DatabaseClient db;

    @MockitoBean
    protected IdentityStoreAuthorizationClient storeAuthorization;

    @BeforeEach
    void authorizeIdentityScopeByDefault() {
        lenient().when(storeAuthorization.authorize(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    String storeId = invocation.getArgument(2);
                    return Mono.just(new IdentityStoreAuthorizationClient.Authorization(
                            true, invocation.getArgument(0), invocation.getArgument(1), storeId,
                            "manager", storeId == null ? "organization" : "store", "finance_transaction"));
                });
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        r.add("marketplace.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("identity-assertion.enabled", () -> "true");
        registerServiceKeyring(r, "marketplace");
        r.add("object-storage.enabled", () -> "false");
        // outbox 发布器在 IT 里必须关掉：默认 bootstrap 是 `kafka:9092`（compose 内部名），
        // 测试跑在宿主机上解析不到，KafkaTemplate 会在**事件循环线程**上阻塞等 metadata 到 60s 超时，
        // 把整个 WebFlux 服务饿死 → 所有请求「Timeout on blocking read」。
        // 各 IT 断言的是 outbox **表里的行**（见 outboxCount），从不校验真实投递，故关掉不削弱任何覆盖。
        r.add("marketplace.outbox.enabled", () -> "false");
        // 对账/contest 派发器默认关：避免普通 IT 后台扫描其他用例的 durable 行；专用测试显式调用 seam。
        r.add("marketplace.reconciliation.dispatcher-enabled", () -> "false");
        r.add("marketplace.contest.dispatcher-enabled", () -> "false");
        r.add("marketplace.commerce.dispatcher-enabled", () -> "false");
        r.add("marketplace.settlement.day-seconds", () -> "1");
        r.add("spring.temporal.test-server.enabled", () -> "true");
        // marketplace 测试 classpath 上还有 identity-service plain jar（VerificationNotificationCrossKafkaIT
        // 的跨服务 e2e 依赖），其 db/migration 与本模块同路径会 Flyway 版本冲突——统一钉到本模块
        // resources 的实体目录，classpath 扫描不再看见外部迁移。
        r.add("marketplace.flyway.locations", () -> "filesystem:" + marketplaceMigrationDir());
        // 让 DefaultErrorWebExceptionHandler 的 4xx 响应携带异常消息（未被 @RestControllerAdvice
        // 捕获的解码/参数异常只有默认信封），否则集成排障只能盲猜。
        r.add("server.error.include-message", () -> "always");
    }

    /** 本模块迁移在本模块 build/resources 目录（classpath 实体路径），直接取父目录。 */
    public static String marketplaceMigrationDir() {
        try {
            java.net.URL resource = MarketplaceItSupport.class
                    .getClassLoader().getResource("db/migration/V1__init_task.sql");
            if (resource == null) {
                throw new IllegalStateException("marketplace migrations not on classpath");
            }
            return new java.io.File(resource.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("invalid marketplace migration location", e);
        }
    }

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 签一个断言（org/tier 为 null——4B 发布限额/tier 闸门前用于非发布场景或预期 403）。 */
    protected String sign(String accountId, String activeIdentityType) {
        return sign(accountId, activeIdentityType, null, null);
    }

    /** 签一个带 org/tier 的断言（Slice 4B：发布限额按 tier、org 归属校验按 organizationId）。 */
    protected String sign(String accountId, String activeIdentityType, String organizationId, String permissionTier) {
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-marketplace").sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, permissionTier,
                "cookie-session", "level1", null, "r", "t",
                "grassland-marketplace", now, now.plusSeconds(60), null, null));
    }

    /**
     * 签一个带平台角色的断言（GL-P1-OPS-001：运营处置台按 {@code app_users.role} 判定，非业务身份）。
     *
     * <p>{@code activeIdentityType} 留 null —— 运营操作与「选了哪个业务视角」正交，
     * 这正是端点必须按 role 判定的原因（历史上按 activeIdentityType 建模职能会导致恒 403）。
     */
    protected String signWithRole(String accountId, String role) {
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-marketplace").sign(new IdentityAssertion(
                accountId, null, "sid-" + accountId, null, null,
                "cookie-session", "level1", null, "r", "t",
                "grassland-marketplace", now, now.plusSeconds(60), null, null, role));
    }

    /** 签一个服务间断言（Slice 12：trust 调内部争议参与方授权端点）。 */
    protected String signService(String principal) {
        Instant now = Instant.now();
        return serviceSigner(principal, "grassland-marketplace").sign(new IdentityAssertion(
                "service:" + principal, null, null, null, null,
                "service", "internal", null, "r", "t",
                "grassland-marketplace", now, now.plusSeconds(30), "service", principal));
    }
}
