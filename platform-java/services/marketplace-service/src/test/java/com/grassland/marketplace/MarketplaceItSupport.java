package com.grassland.marketplace;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        r.add("marketplace.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("identity-assertion.enabled", () -> "true");
        r.add("identity-assertion.secret", () -> "test-secret-32-chars-min!!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
        r.add("object-storage.enabled", () -> "false");
        r.add("spring.temporal.test-server.enabled", () -> "true");
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
        return signer.sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, permissionTier,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), null, null));
    }
}
