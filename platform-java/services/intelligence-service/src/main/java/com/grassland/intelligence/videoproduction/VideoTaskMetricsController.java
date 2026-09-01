package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 治理台视频任务监控（任务书 #65 卡7，§3 契约）：{@code GET /api/admin/video-production/metrics
 * ?window=7d|30d}，admin-only（沿用治理台 requireAdmin），只读。
 */
@RestController
@RequestMapping("/api/admin/video-production")
public class VideoTaskMetricsController {

    private final IntelligenceCallerResolver callers;
    private final VideoTaskMetricsService metrics;

    public VideoTaskMetricsController(IntelligenceCallerResolver callers, VideoTaskMetricsService metrics) {
        this.callers = callers;
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public Mono<Map<String, Object>> metrics(@RequestParam(defaultValue = "7d") String window,
            ServerWebExchange exchange) {
        String safeWindow = "30d".equals(window) ? "30d" : "7d";
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> metrics.metrics(safeWindow)
                        .map(data -> Map.<String, Object>of("success", true, "data", data)));
    }
}
