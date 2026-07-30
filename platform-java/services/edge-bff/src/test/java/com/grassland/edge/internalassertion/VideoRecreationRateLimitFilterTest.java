package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
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

/** 跨迁移/legacy 上游保持视频改编 router-wide 10/min 的回归测试。 */
@ExtendWith(MockitoExtension.class)
class VideoRecreationRateLimitFilterTest {
    @Mock private SessionIdentityResolver identities;
    @Mock private WebFilterChain chain;
    private VideoRecreationRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new VideoRecreationRateLimitFilter(identities,
                Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));
        when(identities.resolve(any())).thenReturn(Mono.just(identity()));
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void migratedImageAndLegacyAdaptContentShareOneAccountBucket() {
        for (int i = 0; i < 10; i++) {
            filter.filter(post("/api/video-recreation/generate-scene-image"), chain).block();
        }
        MockServerWebExchange adaptContent = post("/api/video-recreation/adapt-content");

        filter.filter(adaptContent, chain).block();

        verify(chain, times(10)).filter(any());
        assertThat(adaptContent.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(adaptContent.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("10");
    }

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }

    private static ResolvedIdentity identity() {
        return new ResolvedIdentity("acct", "user", "active", "recommender", "sid", null, null, null, "level1");
    }
}
