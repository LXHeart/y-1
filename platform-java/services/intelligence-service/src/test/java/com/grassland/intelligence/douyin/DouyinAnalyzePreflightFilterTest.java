package com.grassland.intelligence.douyin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** {@link DouyinAnalyzePreflightFilter}：复刻 legacy douyin-analyze 的 20/60s 认证优先限流。 */
@ExtendWith(MockitoExtension.class)
class DouyinAnalyzePreflightFilterTest {

    @Mock
    private IntelligenceCallerResolver callers;
    @Mock
    private WebFilterChain chain;

    private MutableClock clock;
    private DouyinAnalyzePreflightFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        filter = new DouyinAnalyzePreflightFilter(callers, clock);
        lenient().when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void unauthenticatedReturns401BeforeRateLimitOrController() {
        when(callers.resolve(any(ServerHttpRequest.class)))
                .thenReturn(Mono.error(new IntelligenceException(401, "未登录")));

        MockServerWebExchange exchange = post();
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isNull();
        verify(chain, never()).filter(any());
    }

    @Test
    void allowsTwentyThenReturns429WithLegacyHeadersAndMessage() {
        authenticated("acct-1");
        for (int i = 0; i < 20; i++) {
            filter.filter(post(), chain).block();
        }
        verify(chain, times(20)).filter(any());

        MockServerWebExchange rejected = post();
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("20");
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Reset")).isNotNull();
        assertThat(rejected.getResponse().getBodyAsString().block())
                .isEqualTo("{\"success\":false,\"error\":\"视频内容提取请求过于频繁，请稍后再试。\"}");
        verify(chain, times(20)).filter(any());
    }

    @Test
    void rateLimitHeadersTrackFirstAllowedRequest() {
        authenticated("acct-1");
        MockServerWebExchange exchange = post();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("20");
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("19");
    }

    @Test
    void accountKeysAreIndependentAndWindowResets() {
        authenticated("acct-1");
        for (int i = 0; i < 20; i++) {
            filter.filter(post(), chain).block();
        }
        MockServerWebExchange rejected = post();
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        authenticated("acct-2");
        MockServerWebExchange otherAccount = post();
        filter.filter(otherAccount, chain).block();
        assertThat(otherAccount.getResponse().getStatusCode()).isNull();

        clock.advance(Duration.ofSeconds(61));
        authenticated("acct-1");
        MockServerWebExchange afterReset = post();
        filter.filter(afterReset, chain).block();
        assertThat(afterReset.getResponse().getStatusCode()).isNull();
    }

    @Test
    void unrelatedRouteBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/douyin/extract-video").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private void authenticated(String accountId) {
        when(callers.resolve(any(ServerHttpRequest.class))).thenReturn(Mono.just(
                new IntelligenceCallerResolver.Caller(accountId, "merchant", "sid", null, null, "user", null)));
    }

    private static MockServerWebExchange post() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/douyin/analyze-video")
                .header("X-Grassland-Identity", "sig").build());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
