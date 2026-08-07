package com.grassland.trust;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.trust.dispute.MarketplaceEngagementAuthorizationClient;
import com.grassland.trust.judge.IdentityOrganizationMembershipClient;
import com.grassland.trust.judge.MarketplaceReputationClient;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/**
 * trust 集成测试公共基座（草场 Epic 6 Slice 6A）。镜像 {@code FinanceItSupport}：单例 testcontainers postgres；
 * {@code trust.datasource.from-database-url=true} 触发 {@code TrustDataSourceConfig} → Flyway V1 建 dispute_case/trust_outbox；
 * {@code identity-assertion} 注入 signer。提供 {@link #sign}（用户断言）+ {@link #signService}（服务断言）。
 *
 * <p>切片 12 起 {@code POST /api/trust/disputes} 先调 marketplace 授权；这里以 {@link MockitoBean} 替换该出站客户端，
 * 默认对任意 applicationId 放行并回 canonical {@link #MARKETPLACE_ORG}（单测无 marketplace）。需拒绝时在用例内重置桩。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class TrustItSupport {

    /** marketplace 授权桩默认返回的 canonical task organization（真实值由 marketplace 从 task 读取）。 */
    public static final String MARKETPLACE_ORG = UUID.randomUUID().toString();

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

    /** 出站 marketplace 授权客户端：默认放行（回显 applicationId + MARKETPLACE_ORG），用例可重置为拒绝。 */
    @MockitoBean
    protected MarketplaceEngagementAuthorizationClient authorizer;

    /** 出站 marketplace 声誉客户端：默认返回可报名的 Lv5，资格边界测试可覆盖此桩。 */
    @MockitoBean
    protected MarketplaceReputationClient reputationClient;

    /** Identity 权威组织成员关系：默认账号不属于任何组织，资格边界测试可覆盖此桩。 */
    @MockitoBean
    protected IdentityOrganizationMembershipClient identityMemberships;

    @BeforeEach
    void authorizeByDefault() {
        lenient().when(authorizer.authorize(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Mono.just(new MarketplaceEngagementAuthorizationClient.Authorization(
                        inv.getArgument(0), MARKETPLACE_ORG, inv.getArgument(1), false)));
        lenient().when(reputationClient.getLevel(anyString()))
                .thenAnswer(inv -> Mono.just(new MarketplaceReputationClient.LevelResult(
                        inv.getArgument(0), "Lv5", 5, true, 0L)));
        lenient().when(identityMemberships.organizationIds(anyString()))
                .thenReturn(Mono.just(Set.of()));
    }

    /** 用例内显式拒绝授权（非当事方 → trust 403，不创建争议）。 */
    protected void denyAuthorization() {
        when(authorizer.authorize(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

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
        r.add("trust.adjudication.dispatcher.enabled", () -> "false");
        // GL-P2-TRUST-001：测试环境禁用审判启动窗口（48h），避免测试等待
        r.add("trust.adjudication.adjudication-window-enabled", () -> "0");
        // GL-P2-TRUST-001：测试环境禁用争议冷却期（7天），避免测试等待
        r.add("trust.adjudication.dispute-cooldown-hours", () -> "0");
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
