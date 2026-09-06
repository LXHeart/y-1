package com.grassland.intelligence.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>2026-09 任务书 #88：{@code features} 已退役——请求携带 features 整键忽略（WARN 观测）、
 * 响应永不可见、存量原样保留（preserve-on-write：任何 PUT 都不增删改存量 features，清空另见 #47 D19）。
 */
@Component
public class AnalysisSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisSettingsService.class);

    private static final String SETTINGS_TYPE = "analysis";
    private static final List<String> SECRET_KEYS = List.of("apiKey", "apiToken", "appSecret");

    private final UserSettingsRepository repo;
    /** 服务内自持（本服务未注册 ObjectMapper bean；同 SmokeController 等既有约定）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisSettingsService(UserSettingsRepository repo) {
        this.repo = repo;
    }

    /** 读设置（无记录或存量脏数据归一后为空 → 默认）→ mask 密钥。读路径过 schema 守卫，legacy 脏键不出响应。 */
    public Mono<Map<String, Object>> get(String accountId) {
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .map(json -> SettingsSchemaGuard.normalize(SETTINGS_TYPE, json))
                .filter(normalized -> !normalized.isEmpty())
                .defaultIfEmpty(defaultAnalysisSettings())
                .map(AnalysisSettingsService::maskSecrets);
    }

    /**
     * 读当前（归一）→ mask 感知 merge → 归一 + schema 校验 → 写 DB → 返回 masked。
     *
     * <p>任务书 #88：请求体 {@code features} 整键忽略（含坏值——不再 400，记一条 WARN 观测 sunset 遥测，
     * WARN 只含 accountId 不含任何 features 内容）；写路径把存量行中的 {@code features} deepCopy 后原样
     * 放回待写 JSON（preserve-on-write，回滚安全），响应映射永不含该键。8KB 校验作用于实际写库串。
     */
    public Mono<Map<String, Object>> update(String accountId, Map<String, Object> partial) {
        if (partial != null && partial.containsKey("features")) {
            logger.warn("Legacy analysis features payload ignored on settings update (account={})", accountId);
        }
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .defaultIfEmpty(Map.of())
                .flatMap(raw -> {
                    Map<String, Object> normalized = SettingsSchemaGuard.normalize(SETTINGS_TYPE, raw);
                    Map<String, Object> current = normalized.isEmpty() ? defaultAnalysisSettings() : normalized;
                    Map<String, Object> merged = SettingsSchemaGuard.normalize(
                            SETTINGS_TYPE, mergeAnalysisSettings(current, partial == null ? Map.of() : partial));
                    Map<String, Object> stored = new LinkedHashMap<>(merged);
                    Map<String, Object> preserved = preservedFeatures(raw);
                    if (!preserved.isEmpty()) {
                        stored.put("features", preserved);
                    }
                    String serialized = toJson(stored);
                    SettingsSchemaGuard.validate(SETTINGS_TYPE, merged, serialized);
                    return repo.upsert(accountId, SETTINGS_TYPE, serialized)
                            .thenReturn(maskSecrets(merged));
                });
    }

    // ---------- 默认值 ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> defaultAnalysisSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("integrations", Map.of("feishu", Map.of()));
        return settings;
    }

    // ---------- mask ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> maskSecrets(Map<String, Object> settings) {
        Map<String, Object> result = deepCopy(settings);
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
     * 任务书 #88：请求体 {@code features} 整键忽略——返回值不含该键。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeAnalysisSettings(Map<String, Object> current, Map<String, Object> partial) {
        Map<String, Object> result = deepCopy(current);

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

    // ---------- preserve-on-write（任务书 #88） ----------

    /** 存量行 features 原样保留：deepCopy 后随整行写回（不归一、不校验其内容，含未知键）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> preservedFeatures(Map<String, Object> raw) {
        if (raw.get("features") instanceof Map<?, ?> features) {
            return deepCopy((Map<String, Object>) features);
        }
        return Map.of();
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
