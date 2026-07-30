package com.grassland.intelligence.videorecreation;

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
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link VideoRecreationPreflightFilter} 鉴权优先 + 双桶限流单元测试（草场 Slice 9）。 */
@ExtendWith(MockitoExtension.class)
class VideoRecreationPreflightFilterTest {

    @Mock
    private IntelligenceCallerResolver callers;
    @Mock
    private WebFilterChain chain;

    private MutableClock clock;
    private VideoRecreationPreflightFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        filter = new VideoRecreationPreflightFilter(callers, clock);
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void unauthenticatedReturns401AndSkipsChain() {
        when(callers.resolve(any(ServerHttpRequest.class)))
                .thenReturn(Mono.error(new IntelligenceException(401, "未登录")));
        MockServerWebExchange exchange = post("/api/video-recreation/generate-asset-image");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void globalBucketAllowsUpToTenThenRejects() {
        authenticated();
        MockServerWebExchange exchange = post("/api/video-recreation/generate-scene-image");

        for (int i = 0; i < 10; i++) {
            filter.filter(post("/api/video-recreation/generate-scene-image"), chain).block();
        }
        verify(chain, times(10)).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();

        // 第 11 次：global 桶满 → 429，chain 不再放行。
        MockServerWebExchange rejected = post("/api/video-recreation/generate-scene-image");
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, times(10)).filter(any());
    }

    @Test
    void batchBucketRejectsThirdBatchEvenWhenGlobalHasCapacity() {
        authenticated();
        // 仅 3 次批量请求：global（10）远未满，但 batch（2）第 3 次拒绝。
        for (int i = 0; i < 2; i++) {
            filter.filter(post("/api/video-recreation/generate-all-scene-images"), chain).block();
        }
        verify(chain, times(2)).filter(any());

        MockServerWebExchange rejected = post("/api/video-recreation/generate-all-scene-images");
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, times(2)).filter(any());
    }

    @Test
    void rateLimitHeadersReportGlobalBucket() {
        authenticated();
        MockServerWebExchange exchange = post("/api/video-recreation/generate-asset-image");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("10");
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("9");
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Reset")).isNotNull();
    }

    @Test
    void globalWindowResetsAfterSixtySeconds() {
        authenticated();
        for (int i = 0; i < 10; i++) {
            filter.filter(post("/api/video-recreation/generate-scene-image"), chain).block();
        }
        MockServerWebExchange rejected = post("/api/video-recreation/generate-scene-image");
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        clock.advance(Duration.ofSeconds(61));
        MockServerWebExchange afterReset = post("/api/video-recreation/generate-scene-image");
        filter.filter(afterReset, chain).block();
        assertThat(afterReset.getResponse().getStatusCode()).isNull();
        verify(chain, times(11)).filter(any());
    }

    @Test
    void downstreamExceptionPropagatesNotRewrittenTo401() {
        authenticated();
        IntelligenceException upstream = new IntelligenceException(502, "图片生成失败");
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.error(upstream));

        StepVerifier.create(filter.filter(post("/api/video-recreation/generate-asset-image"), chain))
                .verifyError();
    }

    @Test
    void adaptContentSharesGlobalBucketWithImageEndpoints() {
        authenticated();
        // 用 9 次图片端点 + 1 次 adapt-content 填满 global 10/60s；第 11 次 adapt-content → 429。
        for (int i = 0; i < 9; i++) {
            filter.filter(post("/api/video-recreation/generate-scene-image"), chain).block();
        }
        filter.filter(post("/api/video-recreation/adapt-content"), chain).block();
        verify(chain, times(10)).filter(any());

        MockServerWebExchange rejected = post("/api/video-recreation/adapt-content");
        filter.filter(rejected, chain).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, times(10)).filter(any());
    }

    @Test
    void adaptContentRejectsMultipartTooLargeBeforeChain() {
        authenticated();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/video-recreation/adapt-content")
                        .header("Content-Type", "multipart/form-data; boundary=x")
                        .header("Content-Length", String.valueOf(21L * 1024 * 1024))
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chain, never()).filter(any());
    }

    @Test
    void unrelatedRequestBypassesFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/video-recreation/anything").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private void authenticated() {
        when(callers.resolve(any(ServerHttpRequest.class))).thenReturn(Mono.just(
                new IntelligenceCallerResolver.Caller("acct", "recommender", "sid", null, null, "user", null)));
    }

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header("X-Grassland-Identity", "sig").build());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-30T00:00:00Z");
        private final ZoneId zone = ZoneOffset.UTC;

        @Override
        public ZoneId getZone() {
            return zone;
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
