package com.grassland.intelligence.douyin;

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
 * 抖音视频内容分析（草场 GL-P3-MEDIA-001）：{@code POST /api/douyin/analyze-video}。
 *
 * <p>需登录（{@link IntelligenceCallerResolver#resolve} → 401）→ {@link DouyinAnalysisService#analyze}
 * 判定 Java vs 回落：
 * <ul>
 *   <li>{@link DouyinAnalysisOutcome.Java}（progressive + ≤30s + qwen + 公开源）→ 包 {@code {success:true,data}}
 *       （积分已在 service 扣减）。</li>
 *   <li>{@link DouyinAnalysisOutcome.Fallback}（超阈值需 FFmpeg 切片 / 非 qwen）→ {@link LegacyDouyinAnalyzeClient#delegate}
 *       整体转发 legacy（透传 Cookie，legacy 扣积分 + FFmpeg 切片/analysis-media），原样回传 legacy 信封。</li>
 * </ul>
 *
 * <p>edge-bff 路由开关 {@code EDGE_ROUTE_DOUYIN_MEDIA_INTELLIGENCE} 默认 false（与 extract/proxy/download 同 flag）。
 * {@code GET /api/douyin/{analysis-media|audio}/{...}} 不路由（始终 legacy）——FFmpeg 临时媒体与音频提取是
 * Node worker 能力；Java 路径用公开 proxy URL，回落路径的 analysis-media 经公开 origin 指向 legacy。
 */
@RestController
public class DouyinAnalyzeController {

    private final IntelligenceCallerResolver resolver;
    private final DouyinAnalysisService service;
    private final LegacyDouyinAnalyzeClient legacyClient;

    public DouyinAnalyzeController(IntelligenceCallerResolver resolver,
                                   DouyinAnalysisService service,
                                   LegacyDouyinAnalyzeClient legacyClient) {
        this.resolver = resolver;
        this.service = service;
        this.legacyClient = legacyClient;
    }

    @PostMapping("/api/douyin/analyze-video")
    public Mono<Map<String, Object>> analyze(@RequestBody(required = false) Map<String, Object> body,
                                             ServerWebExchange exchange) {
        String proxyVideoUrl = requireProxyVideoUrl(body);
        return resolver.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    String cookie = exchange.getRequest().getHeaders().getFirst(HttpHeaders.COOKIE);
                    return service.analyze(proxyVideoUrl, caller.accountId())
                            .flatMap(outcome -> switch (outcome) {
                                case DouyinAnalysisOutcome.Java java ->
                                        Mono.just(Map.of("success", true, "data", java.data()));
                                case DouyinAnalysisOutcome.Fallback ignored ->
                                        legacyClient.delegate(cookie, body);
                            });
                });
    }

    /** 对齐 legacy schema {@code analyzeDouyinVideoRequest}：缺失/空 → 400「缺少视频代理地址」。 */
    private static String requireProxyVideoUrl(Map<String, Object> body) {
        Object value = body == null ? null : body.get("proxyVideoUrl");
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IntelligenceException(400, "缺少视频代理地址");
        }
        return text.trim();
    }
}
