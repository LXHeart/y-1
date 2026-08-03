package com.grassland.intelligence.douyin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link DouyinExtractPreflightFilter}：复刻 legacy douyin-extract 的 20/60s 匿名 IP 限流。
 * IP 取 {@code X-Forwarded-For} 最右一跳（防左侧伪造轮换绕过）。
 */
@ExtendWith(MockitoExtension.class)
class DouyinExtractPreflightFilterTest {

    @Mock
    private WebFilterChain chain;

    private MutableClock clock;
    private DouyinExtractPreflightFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        filter = new DouyinExtractPreflightFilter(clock);
        lenient().when(chain.filter(any(org.springframework.web.server.ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void allowsTwentyPerIpThenReturns429WithLegacyMessage() {
        for (int i = 0; i < 20; i++) {
            filter.filter(post("203.0.113.7"), chain).block();
        }
        verify(chain, times(20)).filter(any());

        MockServerWebExchange rejected = post("203.0.113.7");
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("20");
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getResponse().getBodyAsString().block())
                .isEqualTo("{\"success\":false,\"error\":\"提取请求过于频繁，请稍后再试。\"}");
    }

    @Test
    void rightmostForwardedHopIsTheBucketKey() {
        for (int i = 0; i < 20; i++) {
            filter.filter(post("10.0.0.1", "spoofed, 10.0.0.1"), chain).block();
        }
        // 左侧伪造值变化不影响桶（最右一跳恒定）→ 仍然受限。
        MockServerWebExchange rejected = post("10.0.0.1", "other-spoof, 10.0.0.1");
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 最右一跳不同 → 独立桶，不受前者限制。
        MockServerWebExchange otherIp = post("10.0.0.2", "spoofed, 10.0.0.2");
        filter.filter(otherIp, chain).block();
        assertThat(otherIp.getResponse().getStatusCode()).isNull();
    }

    @Test
    void windowResetsAfterSixtySeconds() {
        for (int i = 0; i < 20; i++) {
            filter.filter(post("203.0.113.9"), chain).block();
        }
        clock.advance(Duration.ofSeconds(61));
        MockServerWebExchange afterReset = post("203.0.113.9");
        filter.filter(afterReset, chain).block();
        assertThat(afterReset.getResponse().getStatusCode()).isNull();
    }

    @Test
    void unrelatedRouteBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/douyin/analyze-video").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private static MockServerWebExchange post(String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/douyin/extract-video")
                .remoteAddress(new java.net.InetSocketAddress(remoteIp, 12345)).build());
    }

    private static MockServerWebExchange post(String remoteIp, String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/douyin/extract-video")
                .remoteAddress(new java.net.InetSocketAddress(remoteIp, 12345))
                .header("X-Forwarded-For", forwardedFor).build());
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
