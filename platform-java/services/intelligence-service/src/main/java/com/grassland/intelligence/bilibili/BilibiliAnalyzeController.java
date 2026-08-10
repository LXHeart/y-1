package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bilibili 视频内容分析（草场 Slice 13 Stage 5）：{@code POST /api/bilibili/analyze-video}。
 *
 * <p>需登录；短 progressive 视频直接交给 Qwen，DASH/长视频由 Java mux/切片并经 analysis-media 回源。
 * 整次分析只扣一次积分。Edge 开关默认开启，显式关闭可整族回切 legacy。
 */
@RestController
public class BilibiliAnalyzeController {

    private final IntelligenceCallerResolver resolver;
    private final BilibiliAnalysisService service;

    public BilibiliAnalyzeController(IntelligenceCallerResolver resolver,
                                     BilibiliAnalysisService service) {
        this.resolver = resolver;
        this.service = service;
    }

    @PostMapping("/api/bilibili/analyze-video")
    public Mono<Map<String, Object>> analyze(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        String proxyVideoUrl = requireProxyVideoUrl(body);
        return resolver.resolve(exchange.getRequest())
                .flatMap(caller -> service.analyze(proxyVideoUrl, caller.accountId())
                        .map(outcome -> Map.<String, Object>of("success", true, "data", outcome.data())));
    }

    private static String requireProxyVideoUrl(Map<String, Object> body) {
        Object value = body == null ? null : body.get("proxyVideoUrl");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IntelligenceException(400, "缺少可分析的视频地址");
        }
        return text.trim();
    }
}
