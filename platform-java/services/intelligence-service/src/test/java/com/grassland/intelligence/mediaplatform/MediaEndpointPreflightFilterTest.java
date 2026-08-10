package com.grassland.intelligence.mediaplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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

@ExtendWith(MockitoExtension.class)
class MediaEndpointPreflightFilterTest {
    @Mock private WebFilterChain chain;
    private MediaEndpointPreflightFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MediaEndpointPreflightFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void keepsLegacyLimitsForEveryMigratedMediaEndpoint() {
        Map<String, Integer> expected = Map.of(
                "POST /api/bilibili/extract-video", 20,
                "GET /api/bilibili/proxy/token", 120,
                "GET /api/bilibili/download/token", 30,
                "GET /api/bilibili/analysis-media/id", 300,
                "GET /api/douyin/proxy/token", 120,
                "GET /api/douyin/download/token", 30,
                "GET /api/douyin/audio/token", 20,
                "GET /api/douyin/analysis-media/id", 300);

        expected.forEach((request, limit) -> {
            String[] parts = request.split(" ", 2);
            var rule = filter.ruleFor(parts[0], parts[1]);
            assertThat(rule).as(request).isNotNull();
            assertThat(rule.limit()).as(request).isEqualTo(limit);
        });
    }

    @Test
    void rejectsTwentyFirstAudioRequestWithLegacyEnvelope() {
        for (int i = 0; i < 20; i++) filter.filter(get("/api/douyin/audio/token", "203.0.113.7"), chain).block();

        MockServerWebExchange rejected = get("/api/douyin/audio/token", "203.0.113.7");
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("20");
        assertThat(rejected.getResponse().getBodyAsString().block())
                .isEqualTo("{\"success\":false,\"error\":\"音频提取请求过于频繁，请稍后再试。\"}");
    }

    @Test
    void pathBoundaryAndUnrelatedMethodsBypassFilter() {
        MockServerWebExchange sibling = get("/api/douyin/audiox/token", "203.0.113.8");
        filter.filter(sibling, chain).block();
        verify(chain).filter(sibling);

        assertThat(filter.ruleFor("POST", "/api/douyin/audio/token")).isNull();
        assertThat(filter.ruleFor("POST", "/api/bilibili/extract-video/extra")).isNull();
    }

    private static MockServerWebExchange get(String path, String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .remoteAddress(new java.net.InetSocketAddress(ip, 12345)).build());
    }
}
