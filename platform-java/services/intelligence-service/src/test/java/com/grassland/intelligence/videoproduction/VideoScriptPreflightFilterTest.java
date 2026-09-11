package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.assertion.TestAssertionHelper;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.time.Clock;
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

/** body 前置身份/限流闸门：未登录不进入 chain；每账号每分钟前 10 次放行，第 11 次 429。 */
class VideoScriptPreflightFilterTest {

    private static final String AUDIENCE = "grassland-intelligence";
    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final Instant SIGNING_NOW = Instant.now();

    private final IdentityAssertionSigner signer =
            TestAssertionHelper.userSigner("edge-bff", AUDIENCE);
    private final IntelligenceCallerResolver callers =
            new IntelligenceCallerResolver(signer, "X-Grassland-Identity");
    private final VideoScriptPreflightFilter filter =
            new VideoScriptPreflightFilter(callers, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("无断言 → body 前 401，chain 不调用")
    void unauthenticatedRejectedBeforeChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/video-production/generate-script").build());
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
    @DisplayName("同账号前 10 次放行，第 11 次 429")
    void rateLimitsPerAccount() {
        String token = sign("acc-1");
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < 10; i++) {
            filter.filter(exchange(token), chain).block();
        }
        MockServerWebExchange eleventh = exchange(token);
        filter.filter(eleventh, chain).block();

        assertThat(calls).hasValue(10);
        assertThat(eleventh.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("非目标路径不做断言/限流，直接放行")
    void otherPathBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/article-generation/titles").build());
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("C100-08 回归：replay 防护开启时，预检闸不得吃掉 jti——chain 内控制器 resolve 仍须成功")
    void preflightMustNotConsumeReplayJti() {
        // 生产形制（compose 默认 IDENTITY_ASSERTION_REPLAY_ENABLED=true）：同 token 双重
        // verifyReactive 会被 replay 判重放 → 控制器 401「未登录」。预检闸改用非消费验签后，
        // 控制器是同请求唯一 jti 消费点。chain 内模拟控制器的正式 resolve。
        com.grassland.identity.assertion.IdentityAssertionProperties.KeyEntry entry =
                new com.grassland.identity.assertion.IdentityAssertionProperties.KeyEntry(
                        "edge-bff-user-intelligence-test-v1", "edge-bff", "user", AUDIENCE,
                        TestAssertionHelper.DEFAULT_SECRET);
        com.grassland.identity.assertion.IdentityAssertionProperties properties =
                new com.grassland.identity.assertion.IdentityAssertionProperties(
                        true, 60, AUDIENCE, null, 0, null, "edge-bff",
                        java.util.List.of(entry), java.util.List.of(entry),
                        new com.grassland.identity.assertion.IdentityAssertionProperties.ReplayProtectionConfig(false));
        IdentityAssertionSigner guardedSigner = new IdentityAssertionSigner(
                com.grassland.identity.assertion.PropertiesKeyring.from(properties), "edge-bff",
                new com.grassland.identity.assertion.InMemoryAssertionReplayGuard(true), java.time.Duration.ZERO);
        IntelligenceCallerResolver guardedCallers =
                new IntelligenceCallerResolver(guardedSigner, "X-Grassland-Identity");
        VideoScriptPreflightFilter guardedFilter =
                new VideoScriptPreflightFilter(guardedCallers, Clock.fixed(NOW, ZoneOffset.UTC));

        String token = guardedSigner.sign(new IdentityAssertion(
                "acc-replay", "merchant", "sid-replay", null, null,
                "cookie-session", "level1", null, "r", "t",
                AUDIENCE, SIGNING_NOW.minusSeconds(1), SIGNING_NOW.plusSeconds(60), null, null));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/video-production/tasks")
                .header("X-Grassland-Identity", token)
                .build());
        AtomicInteger controllerResolved = new AtomicInteger();
        // 模拟控制器：chain 内做正式（消费 jti 的）resolve——修复前这里 401
        WebFilterChain chain = exchangeInChain -> guardedCallers.requireUser(exchangeInChain.getRequest())
                .doOnSuccess(caller -> controllerResolved.incrementAndGet())
                .then();

        guardedFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .as("预检放行后控制器 resolve 不得被 replay 判重放")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controllerResolved).hasValue(1);
    }

    private MockServerWebExchange exchange(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/video-production/generate-script")
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
