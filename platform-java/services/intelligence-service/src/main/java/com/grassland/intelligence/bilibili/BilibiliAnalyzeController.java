package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bilibili 视频内容分析（草场 Slice 13 Stage 5）：{@code POST /api/bilibili/analyze-video}。
 *
 * <p>需登录（{@link IntelligenceCallerResolver#resolve} → 401）→ {@link BilibiliAnalysisService#analyze} 判定 Java vs 回落：
 * <ul>
 *   <li>{@link BilibiliAnalysisOutcome.Java} → 包 {@code {success:true,data}}（积分已在 service 扣减）。</li>
 *   <li>{@link BilibiliAnalysisOutcome.Fallback} → {@link LegacyBilibiliAnalyzeClient#delegate} 整体转发 legacy（透传 Cookie，
 *       legacy 扣积分 + FFmpeg/Coze），原样回传 legacy 信封。</li>
 * </ul>
 *
 * <p>edge-bff 路由开关 {@code EDGE_ROUTE_BILIBILI_MEDIA_INTELLIGENCE} 默认 false（与 extract/proxy/download 同 flag）。
 * {@code GET /api/bilibili/analysis-media/{id}} 不路由（始终 legacy）——Java 路径用公开 proxy URL，回落路径的
 * analysis-media 经公开 origin 指向 legacy。
 */
@RestController
public class BilibiliAnalyzeController {

    private final IntelligenceCallerResolver resolver;
    private final BilibiliAnalysisService service;
    private final LegacyBilibiliAnalyzeClient legacyClient;

    public BilibiliAnalyzeController(IntelligenceCallerResolver resolver,
                                     BilibiliAnalysisService service,
                                     LegacyBilibiliAnalyzeClient legacyClient) {
        this.resolver = resolver;
        this.service = service;
        this.legacyClient = legacyClient;
    }

    @PostMapping("/api/bilibili/analyze-video")
    public Mono<Map<String, Object>> analyze(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        String proxyVideoUrl = requireProxyVideoUrl(body);
        return resolver.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    String cookie = exchange.getRequest().getHeaders().getFirst(HttpHeaders.COOKIE);
                    return service.analyze(proxyVideoUrl, caller.accountId())
                            .flatMap(outcome -> switch (outcome) {
                                case BilibiliAnalysisOutcome.Java java ->
                                        Mono.just(Map.of("success", true, "data", java.data()));
                                case BilibiliAnalysisOutcome.Fallback ignored ->
                                        legacyClient.delegate(cookie, body);
                            });
                });
    }

    private static String requireProxyVideoUrl(Map<String, Object> body) {
        Object value = body == null ? null : body.get("proxyVideoUrl");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IntelligenceException(400, "缺少可分析的视频地址");
        }
        return text.trim();
    }
}
