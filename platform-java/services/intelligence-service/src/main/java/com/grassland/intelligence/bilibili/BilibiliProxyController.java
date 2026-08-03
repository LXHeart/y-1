package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.mediaplatform.LegacyMediaProxyClient;
import com.grassland.intelligence.mediaplatform.VideoRangeProxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bilibili 视频代理/下载（草场 Slice 13 Stage 4）。{@code GET /api/bilibili/{proxy|download}/{token}}。
 *
 * <p>解 token 得 {@link BilibiliMediaTarget}：progressive→{@link VideoRangeProxy}（Java Range/206，download 附加
 * {@code Content-Disposition}）；dash→{@link LegacyMediaProxyClient}（回落 legacy FFmpeg mux，共享 secret 验签）。
 * token 凭证错误（400/403/410）经全局 handler 转 {@code {success:false,error}}。公开/token-gated，无 auth。
 */
@RestController
public class BilibiliProxyController {

    /** progressive 上游地址守卫：https + Bilibili 受信视频主机（SSRF 边界，含重定向目标复验）。 */
    private static final Predicate<URI> VIDEO_URL_GUARD = uri ->
            "https".equalsIgnoreCase(uri.getScheme()) && BilibiliHosts.isAllowedVideoHost(uri.getHost());

    private final BilibiliProxyToken tokenCodec;
    private final VideoRangeProxy videoRangeProxy;
    private final LegacyMediaProxyClient legacyMediaProxyClient;

    public BilibiliProxyController(
            BilibiliProxyToken tokenCodec,
            VideoRangeProxy videoRangeProxy,
            LegacyMediaProxyClient legacyMediaProxyClient) {
        this.tokenCodec = tokenCodec;
        this.videoRangeProxy = videoRangeProxy;
        this.legacyMediaProxyClient = legacyMediaProxyClient;
    }

    @GetMapping("/api/bilibili/proxy/{token}")
    public Mono<Void> proxy(@PathVariable String token, ServerWebExchange exchange) {
        return handle(tokenCodec.parse(token), exchange, false, token);
    }

    @GetMapping("/api/bilibili/download/{token}")
    public Mono<Void> download(@PathVariable String token, ServerWebExchange exchange) {
        return handle(tokenCodec.parse(token), exchange, true, token);
    }

    private Mono<Void> handle(BilibiliMediaTarget target, ServerWebExchange exchange, boolean download, String token) {
        String range = exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE);
        return switch (target) {
            case BilibiliMediaTarget.Progressive p -> videoRangeProxy.stream(
                    new VideoRangeProxy.Request(
                            p.playableVideoUrl(), range, p.requestHeaders(),
                            download ? buildContentDisposition(p.filename()) : null,
                            VIDEO_URL_GUARD, "Bilibili"),
                    exchange.getResponse());
            case BilibiliMediaTarget.Dash d -> legacyMediaProxyClient.proxy(
                    "/api/bilibili/" + (download ? "download" : "proxy") + "/" + token,
                    range, exchange.getResponse());
        };
    }

    private static String buildContentDisposition(String filename) {
        String name = (filename == null || filename.isBlank()) ? "bilibili-video.mp4" : filename;
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
