package com.grassland.intelligence.media;

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

/** media upload-ticket 频率闸门：仅 POST /api/media/upload-tickets 命中；同账号前 10 次放行，第 11 次 429；匿名按 IP。 */
class MediaUploadTicketPreflightFilterTest {

    private static final String AUDIENCE = "grassland-intelligence";
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
    private static final Instant SIGNING_NOW = Instant.now();
    private static final String PATH = "/api/media/upload-tickets";

    private final IdentityAssertionSigner signer =
            TestAssertionHelper.userSigner("edge-bff", AUDIENCE);
    private final IntelligenceCallerResolver callers =
            new IntelligenceCallerResolver(signer, "X-Grassland-Identity");
    private final MediaUploadTicketPreflightFilter filter =
            new MediaUploadTicketPreflightFilter(callers, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("非目标路径直接放行（confirm / 读 / 删除 不受限流）")
    void nonTargetPathBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/media/abc-123/confirm").build());
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(calls).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("同账号前 10 次放行，第 11 次 429 且带 RateLimit 头")
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
        assertThat(eleventh.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("10");
        assertThat(eleventh.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(eleventh.getResponse().getHeaders().getFirst("RateLimit-Reset")).isEqualTo("60");
    }

    @Test
    @DisplayName("匿名请求按 IP 限流：未超限放行（交 controller 鉴权），超限 429")
    void anonymousRateLimitedByIp() {
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < 10; i++) {
            filter.filter(exchangeNoAssertion("203.0.113.7"), chain).block();
        }
        MockServerWebExchange over = exchangeNoAssertion("203.0.113.7");
        filter.filter(over, chain).block();

        assertThat(calls).hasValue(10);
        assertThat(over.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("不同账号各自独立窗口，互不挤占")
    void independentWindowsPerAccount() {
        AtomicInteger calls = new AtomicInteger();
        WebFilterChain chain = ignored -> {
            calls.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < 10; i++) {
            filter.filter(exchange(sign("acc-a")), chain).block();
        }
        MockServerWebExchange other = exchange(sign("acc-b"));
        filter.filter(other, chain).block();

        assertThat(other.getResponse().getStatusCode()).isNull();
        assertThat(calls).hasValue(11);
    }

    private MockServerWebExchange exchange(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(PATH)
                .header("X-Grassland-Identity", token)
                .build());
    }

    private MockServerWebExchange exchangeNoAssertion(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(PATH)
                .header("X-Forwarded-For", forwardedFor)
                .build());
    }

    private String sign(String accountId) {
        return signer.sign(new IdentityAssertion(
                accountId, "merchant", "sid-" + accountId, null, null,
                "cookie-session", "level1", null, "r", "t",
                AUDIENCE, SIGNING_NOW.minusSeconds(1), SIGNING_NOW.plusSeconds(60), null, null));
    }
}
