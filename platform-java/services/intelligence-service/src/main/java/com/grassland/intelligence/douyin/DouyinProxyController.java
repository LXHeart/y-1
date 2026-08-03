package com.grassland.intelligence.douyin;

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
 * 抖音视频代理/下载（草场 GL-P3-MEDIA-001）。
 * {@code GET /api/douyin/{proxy|download}/{token}}。
 *
 * <p>解 token 得 {@link DouyinMediaTarget}：使用 {@link VideoRangeProxy} 进行流式代理（Java Range/206），
 * download 附加 {@code Content-Disposition}。
 *
 * <p>token 凭证错误（400/403/410）经全局 handler 转 {@code {success:false,error}}。
 * 公开/token-gated，无 auth。
 */
@RestController
public class DouyinProxyController {

    /** 上游地址守卫：https + 抖音受信视频主机（SSRF 边界）。 */
    private static final Predicate<URI> VIDEO_URL_GUARD = uri ->
            "https".equalsIgnoreCase(uri.getScheme()) && DouyinHosts.isAllowedVideoHost(uri.getHost());

    private final DouyinProxyToken tokenCodec;
    private final VideoRangeProxy videoRangeProxy;

    public DouyinProxyController(
            DouyinProxyToken tokenCodec,
            VideoRangeProxy videoRangeProxy) {
        this.tokenCodec = tokenCodec;
        this.videoRangeProxy = videoRangeProxy;
    }

    @GetMapping("/api/douyin/proxy/{token}")
    public Mono<Void> proxy(@PathVariable String token, ServerWebExchange exchange) {
        return handle(token, exchange, false);
    }

    @GetMapping("/api/douyin/download/{token}")
    public Mono<Void> download(@PathVariable String token, ServerWebExchange exchange) {
        return handle(token, exchange, true);
    }

    private Mono<Void> handle(String token, ServerWebExchange exchange, boolean download) {
        DouyinMediaTarget target = tokenCodec.parse(token);
        String range = exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE);
        return videoRangeProxy.stream(
                new VideoRangeProxy.Request(
                        target.playableVideoUrl(),
                        range,
                        target.requestHeaders(),
                        download ? buildContentDisposition(target.filename()) : null,
                        VIDEO_URL_GUARD),
                exchange.getResponse());
    }

    private static String buildContentDisposition(String filename) {
        String name = (filename == null || filename.isBlank())
                ? "douyin-video.mp4"
                : filename;
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
