package com.grassland.trust;

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
 * trust 集成测试公共基座（草场 Epic 6 Slice 6A）。镜像 {@code FinanceItSupport}：单例 testcontainers postgres；
 * {@code trust.datasource.from-database-url=true} 触发 {@code TrustDataSourceConfig} → Flyway V1 建 dispute_case/trust_outbox；
 * {@code identity-assertion} 注入 signer。提供 {@link #sign}（用户断言）+ {@link #signService}（服务断言）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class TrustItSupport {

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
        r.add("trust.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("trust.outbox.enabled", () -> "false");
        r.add("identity-assertion.enabled", () -> "true");
        r.add("identity-assertion.secret", () -> "test-secret-32-chars-min!!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
        // 6C 起 trust-service 引入 Temporal：IT 用内存 test-server（免 temporal 容器），镜像 marketplace。
        r.add("spring.temporal.test-server.enabled", () -> "true");
    }

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 签一个带 org/tier 的用户断言（merchant 开争议/裁决；其它 activeType 用于 403 场景）。 */
    protected String sign(String accountId, String activeIdentityType, String organizationId, String permissionTier) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, permissionTier,
                "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), null, null));
    }

    /** 签一个服务间断言（marketplace 查开放争议用）。 */
    protected String signService(String organizationId, String principal) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(30),
                "service", principal));
    }
}
