package com.grassland.edge.proxy;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 透明流式代理：按 RouteManifest 选上游，零聚合透传 SSE/Multipart/Range。
 */
@Component
public final class RoutingProxyHandler {
    private final UpstreamResolver resolver;
    private final WebClient webClient;

    public RoutingProxyHandler(UpstreamResolver resolver) {
        this.resolver = resolver;
        HttpClient httpClient = HttpClient.create().compress(false).followRedirect(false);
        this.webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        URI upstream = resolver.resolve(method, path);
        if (upstream == null) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        URI target = targetUri(upstream, request.getURI());
        HttpHeaders requestHeaders = ProxyHeaderPolicy.requestHeaders(request.getHeaders());
        Flux<DataBuffer> requestBody = request.getBody();

        return webClient.method(request.getMethod())
            .uri(target)
            .headers(headers -> headers.addAll(requestHeaders))
            .body(BodyInserters.fromDataBuffers(requestBody))
            .exchangeToMono(response -> writeResponse(exchange, response));
    }

    private Mono<Void> writeResponse(ServerWebExchange exchange, ClientResponse response) {
        exchange.getResponse().setStatusCode(response.statusCode());
        String rateLimit = exchange.getResponse().getHeaders().getFirst("RateLimit-Limit");
        String rateRemaining = exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining");
        String rateReset = exchange.getResponse().getHeaders().getFirst("RateLimit-Reset");
        HttpHeaders responseHeaders = ProxyHeaderPolicy.responseHeaders(response.headers().asHttpHeaders());
        exchange.getResponse().getHeaders().putAll(responseHeaders);
        // 若 BFF 已为跨上游路由族（如视频改编）施加共享配额，则不能被下游独立桶的同名头覆盖。
        restoreRateLimitHeaders(exchange, rateLimit, rateRemaining, rateReset);
        return exchange.getResponse().writeWith(response.bodyToFlux(DataBuffer.class));
    }

    static void restoreRateLimitHeaders(
            ServerWebExchange exchange, String limit, String remaining, String reset) {
        if (limit == null) {
            return;
        }
        exchange.getResponse().getHeaders().set("RateLimit-Limit", limit);
        exchange.getResponse().getHeaders().set("RateLimit-Remaining", remaining);
        exchange.getResponse().getHeaders().set("RateLimit-Reset", reset);
    }

    /**
     * 拼上游 URI。**逐字节透传已编码的 path 与 query**。
     *
     * <p>此处刻意用 {@code URI.create(拼好的字符串)} 而不是多参 {@code URI(scheme, authority, path, query, fragment)}
     * 构造器：后者把传入的组件当作**未编码文本**再转义一遍，于是入站的 {@code %3A} 会变成 {@code %253A}，
     * 上游解出字面量 {@code %3A}。这曾让通知中心的 keyset 游标（{@code before=...T08%3A59%3A20Z}）
     * 被 identity 判为非法时间戳返回 400，第二页永远加载不出来（Slice 12 Stage 5 真浏览器 e2e 抓到）。
     * 同理保护 path 里的 {@code %2F}/{@code %20} 等。
     */
    static URI targetUri(URI upstream, URI incoming) {
        try {
            String path = joinPaths(upstream.getRawPath(), incoming.getRawPath());
            String query = incoming.getRawQuery();
            StringBuilder target = new StringBuilder()
                .append(upstream.getScheme()).append("://").append(upstream.getRawAuthority())
                .append(path == null ? "" : path);
            if (query != null && !query.isEmpty()) {
                target.append('?').append(query);
            }
            return new URI(target.toString());
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Unable to construct upstream URI", error);
        }
    }

    private static String joinPaths(String basePath, String requestPath) {
        if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) {
            return requestPath;
        }
        if (basePath.endsWith("/") && requestPath.startsWith("/")) {
            return basePath.substring(0, basePath.length() - 1) + requestPath;
        }
        if (!basePath.endsWith("/") && !requestPath.startsWith("/")) {
            return basePath + "/" + requestPath;
        }
        return basePath + requestPath;
    }
}
