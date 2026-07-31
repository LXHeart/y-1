package com.grassland.intelligence.mediaplatform;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Bilibili DASH 逃生口（草场 Slice 13 Stage 2）：progressive 走 {@link VideoRangeProxy}，DASH 需 FFmpeg mux，
 * 不把 FFmpeg 塞进 WebFlux 请求线程，故把整个请求反向代理回 legacy Express（legacy 用**同 secret** 验 token + mux）。
 *
 * <p>dumb 字节透传：转发客户端 {@code Range}，回写 legacy 的 status + 端到端响应头 + body（剥离 hop-by-hop）。
 * 镜像 edge-bff {@code RoutingProxyHandler.writeResponse} 的零聚合流式。
 */
@Component
public final class LegacyMediaProxyClient {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final WebClient client;
    private final String legacyBaseUrl;

    public LegacyMediaProxyClient(LegacyMediaProxyProperties properties) {
        this.legacyBaseUrl = properties.baseUrl();
        HttpClient http = HttpClient.create().compress(false).followRedirect(true);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /**
     * 反向代理到 legacy。
     *
     * @param legacyPath legacy 上的完整路径（含 query），如 {@code /api/bilibili/proxy/<token>}。
     * @param rangeHeader 客户端 {@code Range} 头（可空）。
     */
    public Mono<Void> proxy(String legacyPath, String rangeHeader, ServerHttpResponse response) {
        return client.get()
                .uri(legacyBaseUrl + legacyPath)
                .headers(headers -> {
                    if (rangeHeader != null && !rangeHeader.isBlank()) {
                        headers.set(HttpHeaders.RANGE, rangeHeader);
                    }
                })
                .exchangeToMono(upstream -> {
                    response.setStatusCode(upstream.statusCode());
                    HttpHeaders out = response.getHeaders();
                    upstream.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                            out.put(name, values);
                        }
                    });
                    return response.writeWith(upstream.bodyToFlux(DataBuffer.class));
                });
    }
}
