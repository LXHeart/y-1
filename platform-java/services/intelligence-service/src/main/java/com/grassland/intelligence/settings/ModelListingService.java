package com.grassland.intelligence.settings;

import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * AI 模型列表 + 验证（GL: settings 迁移）。复刻 legacy {@code qwen-provider.ts} 的 listModels/verifyModel。
 *
 * <p>从用户 analysis settings 拿 baseUrl/apiKey（经 AnalysisSettingsService 读**未 mask** 的值——
 * controller 在 mask 之前传给本 service），调上游 OpenAI 兼容接口。
 */
@Component
public class ModelListingService {

    private final AnalysisSettingsService analysisSettings;
    private final UserSettingsRepository repo;
    private final Duration timeout;
    private final ObjectMapperHolder json;

    public ModelListingService(
            AnalysisSettingsService analysisSettings,
            UserSettingsRepository repo,
            @Value("${hot-items.60s.timeout-ms:8000}") long fallbackTimeout) {
        this.analysisSettings = analysisSettings;
        this.repo = repo;
        this.timeout = Duration.ofMillis(15000); // listModels/verifyModel 用 15s/10s
        this.json = new ObjectMapperHolder();
    }

    /** 列出可用模型（GET {baseUrl}/models）。 */
    public Mono<List<Map<String, Object>>> listModels(String accountId, String feature) {
        return resolveProviderConfig(accountId, feature)
                .flatMap(config -> WebClient.builder()
                        .baseUrl(config.baseUrl())
                        .build()
                        .get()
                        .uri("/models")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(15000))
                        .map(this::parseModels)
                        .onErrorMap(e -> !(e instanceof IntelligenceException),
                                e -> new IntelligenceException(502, "模型列表获取失败：" + e.getMessage())));
    }

    /** 验证模型可用性（POST {baseUrl}/chat/completions, max_tokens=1）。 */
    public Mono<String> verifyModel(String accountId, String feature, String modelId) {
        return resolveProviderConfig(accountId, feature)
                .flatMap(config -> WebClient.builder()
                        .baseUrl(config.baseUrl())
                        .build()
                        .post()
                        .uri("/chat/completions")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("model", modelId,
                                "messages", List.of(Map.of("role", "user", "content", "Hi")),
                                "max_tokens", 1))
                        .retrieve()
                        .toEntity(String.class)
                        .timeout(Duration.ofMillis(10000))
                        .flatMap(response -> response.getStatusCode().is2xxSuccessful()
                                ? Mono.just(modelId)
                                : Mono.<String>error(new IntelligenceException(502, "模型验证失败")))
                        .onErrorMap(e -> !(e instanceof IntelligenceException),
                                e -> new IntelligenceException(502, "模型验证失败：" + e.getMessage())));
    }

    /** 从 analysis settings 解析 feature 的 baseUrl/apiKey（读 DB 原始值，不经过 mask）。 */
    @SuppressWarnings("unchecked")
    private Mono<ProviderConfig> resolveProviderConfig(String accountId, String feature) {
        return repo.findByAccountAndType(accountId, "analysis")
                .map(json::parse)
                .defaultIfEmpty(AnalysisSettingsService.defaultAnalysisSettings())
                .flatMap(settings -> {
                    Map<String, Object> features = (Map<String, Object>) settings.getOrDefault("features", Map.of());
                    Map<String, Object> featureConfig = (Map<String, Object>) features.getOrDefault(feature, Map.of());
                    String baseUrl = (String) featureConfig.get("baseUrl");
                    String apiKey = (String) featureConfig.get("apiKey");
                    if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
                        return Mono.error(new IntelligenceException(400, "请先配置 baseUrl 和 apiKey"));
                    }
                    return Mono.just(new ProviderConfig(baseUrl, apiKey));
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseModels(String body) {
        try {
            Map<String, Object> resp = json.parse(body);
            Object data = resp.get("data");
            if (!(data instanceof List<?> list)) return List.of();
            List<Map<String, Object>> models = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("id", m.get("id"));
                    if (m.get("owned_by") != null) model.put("ownedBy", m.get("owned_by"));
                    models.add(model);
                }
            }
            models.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
            return models;
        } catch (Exception e) {
            return List.of();
        }
    }

    private record ProviderConfig(String baseUrl, String apiKey) {}

    /** 轻量 JSON helper（避免注入 ObjectMapper bean 的循环依赖）。 */
    static class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        @SuppressWarnings("unchecked")
        Map<String, Object> parse(String json) {
            try {
                return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return Map.of();
            }
        }
    }
}
