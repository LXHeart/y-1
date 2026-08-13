package com.grassland.intelligence.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.AssertionReplayGuard;
import com.grassland.identity.assertion.IdentityAssertionProperties;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.assertion.PropertiesKeyring;
import com.grassland.identity.assertion.TestAssertionHelper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/**
 * {@link IntelligenceCallerResolver} 仅信 BFF 断言（无 cookie 回退）：缺/伪造断言 401；
 * {@code requireMerchant}/{@code requireRecommender} 身份不符 403。复刻 marketplace 的鉴权语义。
 */
class IntelligenceCallerResolverAssertionTest {

    private static final String AUDIENCE = "grassland-intelligence";
    private static final String ACCOUNT = "44444444-4444-4444-4444-444444444444";

    private final IdentityAssertionSigner userAssertionSigner =
            TestAssertionHelper.userSigner("edge-bff", AUDIENCE);
    private final IdentityAssertionSigner resolverVerifier = verifier();
    private final IntelligenceCallerResolver resolver =
            new IntelligenceCallerResolver(resolverVerifier, "X-Grassland-Identity");

    private String sign(String activeIdentityType) {
        return signWithRole(activeIdentityType, null);
    }

    /** 带 role 的用户断言（GL-P3-AI-001 requireAdmin 测试用）。role 走 16 参便捷构造器。 */
    private String signWithRole(String activeIdentityType, String role) {
        Instant now = Instant.now();
        return userAssertionSigner.sign(new IdentityAssertion(
                ACCOUNT, activeIdentityType, "sid-" + ACCOUNT, null, null,
                "cookie-session", "level1", null, "r", "t",
                AUDIENCE, now, now.plusSeconds(60), null, null, role));
    }

    /** 造一个服务断言（callerKind=service + principal），镜像 ServiceAssertionIssuer.issueForOrg 的 claims 形状。 */
    private String signService(String principal) {
        Instant now = Instant.now();
        return TestAssertionHelper.serviceSigner(principal, AUDIENCE).sign(new IdentityAssertion(
                "service:" + principal, null, null, null, null,
                "service", "internal", null, "r", "t",
                AUDIENCE, now, now.plusSeconds(30), "service", principal));
    }

    private ServerHttpRequest requestWith(String token) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.post("/api/intelligence/smoke/chat");
        if (token != null) {
            b.header("X-Grassland-Identity", token);
        }
        return b.build();
    }

    @Test
    @DisplayName("有效断言 → resolve 返回 Caller")
    void validAssertionResolves() {
        IntelligenceCallerResolver.Caller c = resolver.resolve(requestWith(sign("recommender"))).block();
        assertThat(c).isNotNull();
        assertThat(c.accountId()).isEqualTo(ACCOUNT);
        assertThat(c.isRecommender()).isTrue();
        assertThat(c.isMerchant()).isFalse();
    }

    @Test
    @DisplayName("缺断言 → 401；伪造断言 → 401")
    void missingOrTamperedRejected() {
        assertThatThrownBy(() -> resolver.resolve(requestWith(null)).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(() -> resolver.resolve(requestWith(sign("merchant") + "tamper")).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    @DisplayName("requireMerchant：非商家 → 403；商家 → 通过")
    void requireMerchantEnforcesRole() {
        assertThatThrownBy(() -> resolver.requireMerchant(requestWith(sign("recommender"))).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
        IntelligenceCallerResolver.Caller c = resolver.requireMerchant(requestWith(sign("merchant"))).block();
        assertThat(c.isMerchant()).isTrue();
    }

    @Test
    @DisplayName("requireRecommender：非推荐官 → 403")
    void requireRecommenderEnforcesRole() {
        assertThatThrownBy(() -> resolver.requireRecommender(requestWith(sign("merchant"))).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    @DisplayName("requireServicePrincipal：marketplace 服务断言通过；用户断言/错 principal → 403")
    void requireServicePrincipalEnforcesService() {
        IntelligenceCallerResolver.Caller c = resolver
                .requireServicePrincipal(requestWith(signService("marketplace")), "marketplace").block();
        assertThat(c).isNotNull();
        assertThat(c.isService()).isTrue();
        assertThat(c.isServicePrincipal("marketplace")).isTrue();

        // 终端用户断言（callerKind=null）→ 403
        assertThatThrownBy(() -> resolver
                .requireServicePrincipal(requestWith(sign("recommender")), "marketplace").block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));

        // 服务断言但 principal 不是 marketplace → 403
        assertThatThrownBy(() -> resolver
                .requireServicePrincipal(requestWith(signService("identity")), "marketplace").block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    @DisplayName("服务断言不可凭 activeIdentityType 冒充商家/推荐官（防冒充）")
    void serviceAssertionCannotImpersonateBusinessIdentities() {
        IntelligenceCallerResolver.Caller c = resolver.resolve(requestWith(signService("marketplace"))).block();
        assertThat(c).isNotNull();
        assertThat(c.isService()).isTrue();
        // 服务断言 callerKind=service → isMerchant/isRecommender 恒 false，即便 activeIdentityType 非空也不可冒充
        assertThat(c.isMerchant()).isFalse();
        assertThat(c.isRecommender()).isFalse();
    }

    @Test
    @DisplayName("requireAdmin：admin 通过并传播 role；user/customer_service → 403；缺断言 → 401")
    void requireAdminEnforcesPlatformRole() {
        // admin → 通过，role 正确传播
        IntelligenceCallerResolver.Caller c =
                resolver.requireAdmin(requestWith(signWithRole("merchant", "admin"))).block();
        assertThat(c).isNotNull();
        assertThat(c.accountId()).isEqualTo(ACCOUNT);
        assertThat(c.role()).isEqualTo("admin");
        assertThat(c.isAdmin()).isTrue();

        // user（无 role）→ 403
        assertThatThrownBy(() -> resolver.requireAdmin(requestWith(sign("merchant"))).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
        // customer_service → 403
        assertThatThrownBy(() -> resolver.requireAdmin(requestWith(signWithRole(null, "customer_service"))).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
        // 缺断言 → 401
        assertThatThrownBy(() -> resolver.requireAdmin(requestWith(null)).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    @DisplayName("服务断言不可凭 role=admin 冒充平台管理员（防冒充）")
    void serviceAssertionCannotImpersonateAdmin() {
        // 服务断言即便构造时塞了 role，isAdmin 也恒 false（callerKind=service）
        Instant now = Instant.now();
        IdentityAssertion serviceWithAdminRole = new IdentityAssertion(
                "service:marketplace", null, null, null, null,
                "service", "internal", null, "r", "t",
                AUDIENCE, now, now.plusSeconds(30), "service", "marketplace", "admin");
        IdentityAssertionSigner marketplaceSigner = TestAssertionHelper.serviceSigner("marketplace", AUDIENCE);
        IntelligenceCallerResolver.Caller c = resolver.resolve(
                requestWith(marketplaceSigner.sign(serviceWithAdminRole))).block();
        assertThat(c).isNotNull();
        assertThat(c.isService()).isTrue();
        assertThat(c.isAdmin()).isFalse();
        assertThatThrownBy(() -> resolver.requireAdmin(
                requestWith(marketplaceSigner.sign(serviceWithAdminRole))).block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    private static IdentityAssertionSigner verifier() {
        List<IdentityAssertionProperties.KeyEntry> verifyKeys = List.of(
                verifyKey("edge-bff", "user"),
                verifyKey("marketplace", "service"),
                verifyKey("identity", "service"));
        IdentityAssertionProperties properties = new IdentityAssertionProperties(
                true, 60, AUDIENCE, null, 5, null, "intelligence",
                List.of(), verifyKeys, new IdentityAssertionProperties.ReplayProtectionConfig(false));
        return new IdentityAssertionSigner(
                PropertiesKeyring.from(properties), "intelligence", AssertionReplayGuard.NO_OP, Duration.ofSeconds(5));
    }

    private static IdentityAssertionProperties.KeyEntry verifyKey(String issuer, String purpose) {
        String kid = issuer + "-" + purpose + "-intelligence-test-v1";
        return new IdentityAssertionProperties.KeyEntry(
                kid, issuer, purpose, AUDIENCE, TestAssertionHelper.DEFAULT_SECRET);
    }
}
