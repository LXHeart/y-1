package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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

    /**
     * 透传是**逐字节**的：入站已编码的 query 不能被再编一次。
     *
     * <p>回归自 Slice 12 Stage 5 真浏览器 e2e——通知中心 keyset 游标 `before=...T08%3A59%3A20Z`
     * 经 BFF 后变成 `%253A`，identity 侧解出字面量 `%3A` 判为非法时间戳返回 400，第二页永远加载不出来。
     * 根因是多参 {@code URI} 构造器会把已编码的 raw 组件当作未编码文本再转义。
     */
    @Test
    void forwardsPercentEncodedQueryWithoutDoubleEncoding() {
        URI target = RoutingProxyHandler.targetUri(
                URI.create("http://identity-service:8082"),
                URI.create("http://localhost:8080/api/me/notifications"
                        + "?limit=20&before=2026-07-31T08%3A59%3A20.774460Z&beforeId=abc"));

        assertThat(target.getRawQuery())
                .isEqualTo("limit=20&before=2026-07-31T08%3A59%3A20.774460Z&beforeId=abc");
        assertThat(target.getRawQuery()).doesNotContain("%25");
    }

    /** 路径里的编码字符（如媒体 key 中的 %2F）同样不能被二次编码。 */
    @Test
    void forwardsPercentEncodedPathWithoutDoubleEncoding() {
        URI target = RoutingProxyHandler.targetUri(
                URI.create("http://intelligence-service:8086"),
                URI.create("http://localhost:8080/api/media/a%2Fb%20c"));

        assertThat(target.getRawPath()).isEqualTo("/api/media/a%2Fb%20c");
        assertThat(target.toString()).isEqualTo("http://intelligence-service:8086/api/media/a%2Fb%20c");
    }

    /** 上游带 base path 时仍要正确拼接，且无 query 时不留下多余的 `?`。 */
    @Test
    void joinsUpstreamBasePathAndOmitsEmptyQuery() {
        URI target = RoutingProxyHandler.targetUri(
                URI.create("http://upstream:8080/base"),
                URI.create("http://localhost:8080/api/health"));

        assertThat(target.toString()).isEqualTo("http://upstream:8080/base/api/health");
    }
}
