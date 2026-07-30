package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class RoutingProxyHandlerTest {
    @Test
    void restoresBffRateLimitHeadersAfterDownstreamHeaderMerge() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/video-recreation/generate-scene-image").build());
        exchange.getResponse().getHeaders().set("RateLimit-Limit", "10");
        exchange.getResponse().getHeaders().set("RateLimit-Remaining", "4");
        exchange.getResponse().getHeaders().set("RateLimit-Reset", "37");

        RoutingProxyHandler.restoreRateLimitHeaders(exchange, "10", "4", "37");

        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("10");
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("4");
        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Reset")).isEqualTo("37");
    }

    @Test
    void doesNotInventBffRateLimitHeadersWhenFilterDidNotSetThem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/example").build());

        RoutingProxyHandler.restoreRateLimitHeaders(exchange, null, null, null);

        assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isNull();
    }
}
