package com.grassland.intelligence.douyin;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 抖音视频内容分析（草场 GL-P3-MEDIA-001）：{@code POST /api/douyin/analyze-video}。
 *
 * <p>需登录；短视频直接交给 Qwen，长视频由 Java FFmpeg 切片并经 analysis-media 回源。
 * 整次分析只扣一次积分。Edge 开关默认开启，显式关闭可整族回切 legacy。
 */
@RestController
public class DouyinAnalyzeController {

    private final IntelligenceCallerResolver resolver;
    private final DouyinAnalysisService service;

    public DouyinAnalyzeController(IntelligenceCallerResolver resolver,
                                   DouyinAnalysisService service) {
        this.resolver = resolver;
        this.service = service;
    }

    @PostMapping("/api/douyin/analyze-video")
    public Mono<Map<String, Object>> analyze(@RequestBody(required = false) Map<String, Object> body,
                                             ServerWebExchange exchange) {
        String proxyVideoUrl = requireProxyVideoUrl(body);
        return resolver.resolve(exchange.getRequest())
                .flatMap(caller -> service.analyze(proxyVideoUrl, caller.accountId())
                        .map(outcome -> Map.<String, Object>of("success", true, "data", outcome.data())));
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
