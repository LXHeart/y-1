package com.grassland.intelligence.settings;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 用户级分析设置 + 首页设置 HTTP 入口（GL: settings 迁移）。
 *
 * <p>全部要求登录（IntelligenceCallerResolver.resolve → accountId）。响应契约与 legacy 1:1（前端零改动）。
 */
@RestController
public class SettingsController {

    private final IntelligenceCallerResolver callers;
    private final AnalysisSettingsService analysisSettings;
    private final HomepageSettingsService homepageSettings;
    private final ModelListingService modelListing;

    public SettingsController(
            IntelligenceCallerResolver callers,
            AnalysisSettingsService analysisSettings,
            HomepageSettingsService homepageSettings,
            ModelListingService modelListing) {
        this.callers = callers;
        this.analysisSettings = analysisSettings;
        this.homepageSettings = homepageSettings;
        this.modelListing = modelListing;
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

    @PostMapping(value = "/api/settings/analysis/models", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> listModels(
            @RequestBody Map<String, Object> body, ServerHttpRequest request) {
        String feature = requireFeature(body);
        return callers.resolve(request)
                .flatMap(caller -> modelListing.listModels(caller.accountId(), feature))
                .map(models -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("models", models))));
    }

    @PostMapping(value = "/api/settings/analysis/verify-model", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> verifyModel(
            @RequestBody Map<String, Object> body, ServerHttpRequest request) {
        String feature = requireFeature(body);
        String model = requireModel(body);
        return callers.resolve(request)
                .flatMap(caller -> modelListing.verifyModel(caller.accountId(), feature, model))
                .map(verified -> ResponseEntity.ok(Map.of("success", true,
                        "data", Map.of("verified", true, "modelId", verified))));
    }

    // ---------- homepage ----------

    @GetMapping("/api/settings/homepage")
    public Mono<ResponseEntity<Map<String, Object>>> getHomepage(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> homepageSettings.get(caller.accountId()))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    @PutMapping(value = "/api/settings/homepage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateHomepage(
            @RequestBody Map<String, Object> body, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> homepageSettings.update(caller.accountId(), body))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    // ---------- helpers ----------

    private static String requireFeature(Map<String, Object> body) {
        Object feature = body.get("feature");
        if (feature == null || feature.toString().isBlank()) {
            throw new IntelligenceException(400, "缺少 feature");
        }
        return feature.toString();
    }

    private static String requireModel(Map<String, Object> body) {
        Object model = body.get("model");
        if (model == null || model.toString().isBlank()) {
            throw new IntelligenceException(400, "缺少 model");
        }
        return model.toString();
    }

    @ExceptionHandler(IntelligenceException.class)
    public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
