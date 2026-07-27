package com.grassland.intelligence.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/**
 * {@link IntelligenceCallerResolver} 仅信 BFF 断言（无 cookie 回退）：缺/伪造断言 401；
 * {@code requireMerchant}/{@code requireRecommender} 身份不符 403。复刻 marketplace 的鉴权语义。
 */
class IntelligenceCallerResolverAssertionTest {

    private static final String SECRET = "test-secret-32-chars-min!!!";
    private static final String AUDIENCE = "grassland-internal";
    private static final String ACCOUNT = "44444444-4444-4444-4444-444444444444";

    private final IdentityAssertionSigner signer =
            new IdentityAssertionSigner(SECRET.getBytes(), AUDIENCE, Duration.ofSeconds(5));
    private final IntelligenceCallerResolver resolver = new IntelligenceCallerResolver(signer, "X-Grassland-Identity");

    private String sign(String activeIdentityType) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                ACCOUNT, activeIdentityType, "sid-" + ACCOUNT, null, null,
                "cookie-session", "level1", null, "r", "t",
                AUDIENCE, now, now.plusSeconds(60), null, null));
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
}
