package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 视频 Range/206 字节透传代理（草场 Slice 13 Stage 2）。仓库此前无 Range 基建；本类以 edge-bff
 * {@code RoutingProxyHandler} 的零聚合 {@code DataBuffer} 流式为模型，逐字对齐 legacy
 * {@code bilibili-stream.service.ts} 的 progressive 分支：
 * <ul>
 *   <li>转发 {@code Accept}/{@code Accept-Language} + token 受信请求头 + 客户端 {@code Range}；</li>
 *   <li>每跳（含首跳与 ≤2 次重定向）经 {@code urlGuard} 复验（https + 平台受信主机），防 SSRF；</li>
 *   <li>上游 ≥400→502、非 {@code video/*} / {@code application/octet-stream}→502；</li>
 *   <li>回写 {@code content-type/length/range/accept-ranges/etag/last-modified} + 上游 status（206 原样）
 *       + {@code Cache-Control: no-store, private}，可选 {@code Content-Disposition}。</li>
 * </ul>
 * 返回 {@code Mono<Void>}：直接写 {@link ServerHttpResponse}（与 edge-bff 透传 handler 同构）。
 */
@Component
public final class VideoRangeProxy {

    static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_REDIRECTS = 2;
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> PASSTHROUGH_RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified");

    private final WebClient client;

    public VideoRangeProxy() {
        HttpClient http = HttpClient.create()
                .compress(false)
                .followRedirect(false)
                .responseTimeout(UPSTREAM_TIMEOUT);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** 一次 progressive 视频透传请求。{@code urlGuard} 校验 scheme+host（含重定向目标）。 */
    public record Request(
            String upstreamUrl,
            String rangeHeader,
            Map<String, String> sanitizedRequestHeaders,
            String contentDisposition,
            Predicate<URI> urlGuard) {}

    public Mono<Void> stream(Request request, ServerHttpResponse response) {
        return validate(request.upstreamUrl(), request.urlGuard())
                .flatMap(url -> stream(request, url, 0, response));
    }

    private Mono<Void> stream(Request request, String url, int redirectCount, ServerHttpResponse response) {
        return client.get()
                .uri(url)
                .headers(headers -> applyUpstreamHeaders(headers, request))
                .exchangeToMono(upstream -> handleUpstream(request, upstream, redirectCount, url, response));
    }

    private void applyUpstreamHeaders(HttpHeaders target, Request request) {
        target.set(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        target.set(HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE);
        if (request.sanitizedRequestHeaders() != null) {
            request.sanitizedRequestHeaders().forEach(target::add);
        }
        if (request.rangeHeader() != null && !request.rangeHeader().isBlank()) {
            target.set(HttpHeaders.RANGE, request.rangeHeader());
        }
    }

    private Mono<Void> handleUpstream(
            Request request, ClientResponse upstream, int redirectCount, String url, ServerHttpResponse response) {
        HttpStatusCode status = upstream.statusCode();
        if (REDIRECT_STATUSES.contains(status.value())) {
            return upstream.releaseBody().then(Mono.defer(() -> {
                if (redirectCount >= MAX_REDIRECTS) {
                    return Mono.error(new IntelligenceException(502, "Bilibili 上游重定向次数过多"));
                }
                String location = upstream.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                return validate(resolveRedirect(url, location), request.urlGuard())
                        .flatMap(next -> stream(request, next, redirectCount + 1, response));
            }));
        }
        if (status.is4xxClientError() || status.is5xxServerError()) {
            return upstream.releaseBody()
                    .then(Mono.error(new IntelligenceException(502,
                            "Bilibili 上游视频请求失败（HTTP " + status.value() + "）")));
        }
        String contentType = upstream.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (!isVideoContentType(contentType)) {
            return upstream.releaseBody()
                    .then(Mono.error(new IntelligenceException(502, "Bilibili 上游未返回视频流")));
        }
        return writeSuccess(request, upstream, response);
    }

    private Mono<Void> writeSuccess(Request request, ClientResponse upstream, ServerHttpResponse response) {
        response.setStatusCode(upstream.statusCode());
        HttpHeaders out = response.getHeaders();
        out.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
        upstream.headers().asHttpHeaders().forEach((name, values) -> {
            if (PASSTHROUGH_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(name, values);
            }
        });
        if (request.contentDisposition() != null && !request.contentDisposition().isBlank()) {
            out.set(HttpHeaders.CONTENT_DISPOSITION, request.contentDisposition());
        }
        return response.writeWith(upstream.bodyToFlux(DataBuffer.class));
    }

    private static Mono<String> validate(String url, Predicate<URI> urlGuard) {
        try {
            URI uri = URI.create(url);
            if (!urlGuard.test(uri)) {
                return Mono.error(new IntelligenceException(502, "视频代理目标地址不被允许"));
            }
            return Mono.just(url);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(502, "视频代理目标地址无效"));
        }
    }

    private static String resolveRedirect(String baseUrl, String location) {
        if (location == null || location.isBlank()) {
            throw new IntelligenceException(502, "Bilibili 上游重定向缺失 Location");
        }
        try {
            return URI.create(baseUrl).resolve(location).toString();
        } catch (Exception e) {
            throw new IntelligenceException(502, "Bilibili 上游重定向地址无效");
        }
    }

    private static boolean isVideoContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("video/") || lower.startsWith("application/octet-stream");
    }
}
