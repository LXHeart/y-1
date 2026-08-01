package com.grassland.intelligence.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 冒烟端点限流闸门（GL-P0-SEC-002）：此前该路径不被任何 preflight filter 覆盖，
 * 登录账号可无节流驱动 Qwen 上游。此处锁死未登录前置拒绝、每账号 5 次/分钟、非目标路径不受影响。
 */
class SmokePreflightFilterTest {

    private static final String SECRET = "test-secret-32-chars-min!!!";
    private static final String AUDIENCE = "grassland-internal";
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final Instant SIGNING_NOW = Instant.now();

    private final IdentityAssertionSigner signer =
            new IdentityAssertionSigner(SECRET.getBytes(), AUDIENCE, Duration.ZERO);
    private final IntelligenceCallerResolver callers =
            new IntelligenceCallerResolver(signer, "X-Grassland-Identity");
    private final SmokePreflightFilter filter =
            new SmokePreflightFilter(callers, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("无断言 → 401，chain 不调用（不进 controller、不扣分、不碰上游）")
    void unauthenticatedRejectedBeforeChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/intelligence/smoke/chat").build());
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("同账号前 5 次放行，第 6 次 429")
    void rateLimitsPerAccount() {
        String token = sign("acc-1");
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < SmokePreflightFilter.MAX_REQUESTS_PER_WINDOW; i++) {
            filter.filter(exchange(token), chain).block();
        }
        MockServerWebExchange overLimit = exchange(token);
        filter.filter(overLimit, chain).block();

        assertThat(calls).hasValue(SmokePreflightFilter.MAX_REQUESTS_PER_WINDOW);
        assertThat(overLimit.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("限流按账号隔离：A 用满不影响 B")
    void windowsArePerAccount() {
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };
        String tokenA = sign("acc-a");
        for (int i = 0; i <= SmokePreflightFilter.MAX_REQUESTS_PER_WINDOW; i++) {
            filter.filter(exchange(tokenA), chain).block();
        }

        MockServerWebExchange forB = exchange(sign("acc-b"));
        filter.filter(forB, chain).block();

        assertThat(forB.getResponse().getStatusCode()).isNull();
        assertThat(calls).hasValue(SmokePreflightFilter.MAX_REQUESTS_PER_WINDOW + 1);
    }

    @Test
    @DisplayName("非目标路径不做断言/限流，直接放行")
    void otherPathBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/article-generation/titles").build());
        AtomicInteger calls = new AtomicInteger();
        filter.filter(exchange, ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(calls).hasValue(1);
    }

    private MockServerWebExchange exchange(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/intelligence/smoke/chat")
                .header("X-Grassland-Identity", token)
                .build());
    }

    private String sign(String accountId) {
        return signer.sign(new IdentityAssertion(
                accountId, "merchant", "sid-" + accountId, null, null,
                "cookie-session", "level1", null, "r", "t",
                AUDIENCE, SIGNING_NOW.minusSeconds(1), SIGNING_NOW.plusSeconds(60), null, null));
    }
}
