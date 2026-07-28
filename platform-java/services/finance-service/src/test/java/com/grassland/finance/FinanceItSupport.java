package com.grassland.finance;

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
 * finance 集成测试公共基座。草场 Epic 4 Slice 4D。
 *
 * <p>单例 testcontainers postgres（全程不重启，端口稳定）；{@code finance.datasource.from-database-url=true}
 * 触发 {@code FinanceDataSourceConfig} → Flyway V1 建 finance_account/finance_outbox；{@code identity-assertion} 注入 signer。
 * 提供 {@link #sign} 签断言（镜像 MarketplaceItSupport）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class FinanceItSupport {

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
        r.add("finance.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("finance.outbox.enabled", () -> "false");
        r.add("identity-assertion.enabled", () -> "true");
        r.add("identity-assertion.secret", () -> "test-secret-32-chars-min!!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
    }

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 签一个带 org/tier 的断言（merchant 用于开户；其它 activeType 用于 403 场景）。 */
    protected String sign(String accountId, String activeIdentityType, String organizationId, String permissionTier) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, permissionTier,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), null, null));
    }

    /** 签一个服务间断言（HLD 11.1 服务身份，Slice 4F）：callerKind=service + principal，带 org 上下文。 */
    protected String signService(String organizationId, String principal) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(30),
                "service", principal));
    }
}
