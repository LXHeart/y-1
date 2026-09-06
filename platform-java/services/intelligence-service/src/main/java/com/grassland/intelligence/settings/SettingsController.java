package com.grassland.intelligence.settings;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 用户级分析设置 HTTP 入口（GL: settings 迁移）。首页设置已随任务书 #47 S7b 升平台配置下线
 * （/api/admin/homepage/hot-config），用户级 homepage 端点删除。
 *
 * <p>仅剩 GET/PUT {@code /api/settings/analysis}（飞书导出凭据，掩码感知 merge）。旧模型列表/验证端点
 * {@code POST /api/settings/analysis/models}、{@code POST /api/settings/analysis/verify-model} 已于
 * 2026-09 随任务书 #88 退役（请求 404）——模型解析的唯一真相收敛到 AI 控制面
 * （{@code /api/ai/keys} BYOK + 治理台平台凭据/平台模型配置）。
 *
 * <p>全部要求登录（IntelligenceCallerResolver.resolve → accountId）。
 */
@RestController
public class SettingsController {

    private final IntelligenceCallerResolver callers;
    private final AnalysisSettingsService analysisSettings;

    public SettingsController(
            IntelligenceCallerResolver callers,
            AnalysisSettingsService analysisSettings) {
        this.callers = callers;
        this.analysisSettings = analysisSettings;
    }

    // ---------- analysis ----------

    @GetMapping("/api/settings/analysis")
    public Mono<ResponseEntity<Map<String, Object>>> getAnalysis(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> analysisSettings.get(caller.accountId()))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    @PutMapping(value = "/api/settings/analysis", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateAnalysis(
            @RequestBody Map<String, Object> body, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> analysisSettings.update(caller.accountId(), body))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    // ---------- helpers ----------

    @ExceptionHandler(IntelligenceException.class)
    public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
