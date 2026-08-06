package com.grassland.intelligence.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 用户级分析设置（GL: settings 迁移）。读写 user_settings 表 type='analysis'。
 *
 * <p>核心是**掩码感知 merge**：前端永远拿到 masked 密钥（****xxxx），PUT 回来时：
 * <ul>
 *   <li>字段缺失 → 保留当前值</li>
 *   <li>值匹配 {@code ****...}（掩码） → 保留当前值（掩码被忽略）</li>
 *   <li>值为空串 → 清空</li>
 *   <li>明文 → 更新</li>
 * </ul>
 * 非密钥字段直接覆盖。复刻 legacy {@code analysis-settings.service.ts} 的 resolveUpdatedSecret/merge 逻辑。
 */
@Component
public class AnalysisSettingsService {

    private static final String SETTINGS_TYPE = "analysis";
    private static final List<String> SECRET_KEYS = List.of("apiKey", "apiToken", "appSecret");

    private final UserSettingsRepository repo;
    /** 服务内自持（本服务未注册 ObjectMapper bean；同 SmokeController 等既有约定）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisSettingsService(UserSettingsRepository repo) {
        this.repo = repo;
    }

    /** 读设置（无记录返回默认）→ mask 密钥。 */
    public Mono<Map<String, Object>> get(String accountId) {
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .defaultIfEmpty(defaultAnalysisSettings())
                .map(AnalysisSettingsService::maskSecrets);
    }

    /** 读当前 → mask 感知 merge → 写 DB → 返回 masked。 */
    public Mono<Map<String, Object>> update(String accountId, Map<String, Object> partial) {
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .defaultIfEmpty(defaultAnalysisSettings())
                .flatMap(current -> {
                    Map<String, Object> merged = mergeAnalysisSettings(current, partial);
                    return repo.upsert(accountId, SETTINGS_TYPE, toJson(merged))
                            .thenReturn(maskSecrets(merged));
                });
    }

    // ---------- 默认值 ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> defaultAnalysisSettings() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("video", Map.of("provider", "qwen"));
        features.put("image", Map.of());
        features.put("article", Map.of());
        features.put("imageGeneration", Map.of());
        features.put("videoProduction", Map.of());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("features", features);
        settings.put("integrations", Map.of("feishu", Map.of()));
        return settings;
    }

    // ---------- mask ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> maskSecrets(Map<String, Object> settings) {
        Map<String, Object> result = deepCopy(settings);
        Map<String, Object> features = (Map<String, Object>) result.getOrDefault("features", Map.of());
        for (Object featureObj : features.values()) {
            if (featureObj instanceof Map<?, ?> feature) {
                maskSecretFields((Map<String, Object>) feature);
            }
        }
        Map<String, Object> integrations = (Map<String, Object>) result.getOrDefault("integrations", Map.of());
        Map<String, Object> feishu = (Map<String, Object>) integrations.getOrDefault("feishu", Map.of());
        maskSecretFields(feishu);
        return result;
    }

    private static void maskSecretFields(Map<String, Object> map) {
        for (String key : SECRET_KEYS) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                if (value instanceof String s && !s.isEmpty()) {
                    map.put(key, maskSecret(s));
                }
            }
        }
    }

    static String maskSecret(String value) {
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    static boolean isMasked(String value) {
        return value != null && value.startsWith("****");
    }

    // ---------- merge ----------

    /**
     * mask 感知 merge：partial 覆盖 current，但密钥字段遵守掩码语义。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeAnalysisSettings(Map<String, Object> current, Map<String, Object> partial) {
        Map<String, Object> result = deepCopy(current);
        Map<String, Object> partialFeatures = (Map<String, Object>) partial.getOrDefault("features", Map.of());
        Map<String, Object> currentFeatures = (Map<String, Object>) result.getOrDefault("features", Map.of());

        for (String featureKey : List.of("video", "image", "article", "imageGeneration", "videoProduction")) {
            if (partialFeatures.containsKey(featureKey)) {
                Map<String, Object> currentFeature = new LinkedHashMap<>(
                        (Map<String, Object>) currentFeatures.getOrDefault(featureKey, Map.of()));
                Map<String, Object> partialFeature = (Map<String, Object>) partialFeatures.get(featureKey);
                mergeFeature(currentFeature, partialFeature);
                currentFeatures.put(featureKey, currentFeature);
            }
        }
        result.put("features", currentFeatures);

        // feishu 合并
        Map<String, Object> partialIntegrations = (Map<String, Object>) partial.getOrDefault("integrations", Map.of());
        if (partialIntegrations.containsKey("feishu")) {
            Map<String, Object> currentIntegrations = (Map<String, Object>) result.getOrDefault("integrations", Map.of());
            Map<String, Object> currentFeishu = new LinkedHashMap<>(
                    (Map<String, Object>) currentIntegrations.getOrDefault("feishu", Map.of()));
            Map<String, Object> partialFeishu = (Map<String, Object>) partialIntegrations.get("feishu");
            mergeFeature(currentFeishu, partialFeishu);
            currentIntegrations.put("feishu", currentFeishu);
            result.put("integrations", currentIntegrations);
        }

        return result;
    }

    /**
     * 合并单个 feature/section：partial 的每个字段按掩码语义覆盖 current。
     */
    private static void mergeFeature(Map<String, Object> current, Map<String, Object> partial) {
        for (Map.Entry<String, Object> entry : partial.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (SECRET_KEYS.contains(key)) {
                // 密钥字段的掩码语义
                if (value == null) continue; // null = 不变
                if (value instanceof String s) {
                    if (s.isEmpty()) {
                        current.remove(key); // 空串 = 清空
                    } else if (isMasked(s)) {
                        continue; // 掩码 = 不变
                    } else {
                        current.put(key, s); // 明文 = 更新
                    }
                }
            } else {
                // 非密钥字段直接覆盖
                if (value == null || (value instanceof String s && s.isEmpty())) {
                    current.remove(key);
                } else {
                    current.put(key, value);
                }
            }
        }
    }

    // ---------- JSON helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return defaultAnalysisSettings();
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize settings", e);
        }
    }

    /** 深拷贝专用（无状态、线程安全，避免每次 new）：merge/mask 不得就地改动调用方传入的 map。 */
    private static final ObjectMapper COPY_MAPPER = new ObjectMapper();

    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        return COPY_MAPPER.convertValue(source, new TypeReference<Map<String, Object>>() {});
    }
}
