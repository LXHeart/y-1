package com.grassland.intelligence.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 用户级首页设置（GL: settings 迁移）。读写 user_settings 表 type='homepage'。
 *
 * <p>hotItems.provider 选择数据源（60s/alapi）；alapiToken 有掩码语义（同 analysis）。
 */
@Component
public class HomepageSettingsService {

    private static final String SETTINGS_TYPE = "homepage";

    private final UserSettingsRepository repo;
    /** 服务内自持（本服务未注册 ObjectMapper bean）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    public HomepageSettingsService(UserSettingsRepository repo) {
        this.repo = repo;
    }

    public Mono<Map<String, Object>> get(String accountId) {
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .defaultIfEmpty(defaultHomepageSettings())
                .map(HomepageSettingsService::maskAlapiToken);
    }

    public Mono<Map<String, Object>> update(String accountId, Map<String, Object> partial) {
        return repo.findByAccountAndType(accountId, SETTINGS_TYPE)
                .map(this::parseJson)
                .defaultIfEmpty(defaultHomepageSettings())
                .flatMap(current -> {
                    Map<String, Object> merged = mergeHomepageSettings(current, partial);
                    return repo.upsert(accountId, SETTINGS_TYPE, toJson(merged))
                            .thenReturn(maskAlapiToken(merged));
                });
    }

    /**
     * 无 accountId（未登录）时返回平台默认设置（provider=60s）。
     */
    public Mono<Map<String, Object>> getOrDefault(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Mono.just(maskAlapiToken(defaultHomepageSettings()));
        }
        return get(accountId);
    }

    static Map<String, Object> defaultHomepageSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("hotItems", Map.of("provider", "60s"));
        return settings;
    }

    // ---------- mask ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> maskAlapiToken(Map<String, Object> settings) {
        Map<String, Object> result = new LinkedHashMap<>(settings);
        Map<String, Object> hotItems = (Map<String, Object>) result.getOrDefault("hotItems", Map.of());
        if (hotItems.containsKey("alapiToken")) {
            Object value = hotItems.get("alapiToken");
            if (value instanceof String s && !s.isEmpty()) {
                hotItems.put("alapiToken", AnalysisSettingsService.maskSecret(s));
            }
        }
        result.put("hotItems", hotItems);
        return result;
    }

    // ---------- merge ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeHomepageSettings(Map<String, Object> current, Map<String, Object> partial) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        Map<String, Object> partialHotItems = (Map<String, Object>) partial.getOrDefault("hotItems", Map.of());
        if (!partialHotItems.isEmpty()) {
            Map<String, Object> currentHotItems = new LinkedHashMap<>(
                    (Map<String, Object>) result.getOrDefault("hotItems", Map.of()));
            // provider 直接覆盖
            if (partialHotItems.containsKey("provider")) {
                currentHotItems.put("provider", partialHotItems.get("provider"));
            }
            // alapiToken 掩码语义
            if (partialHotItems.containsKey("alapiToken")) {
                Object token = partialHotItems.get("alapiToken");
                if (token == null) {
                    currentHotItems.remove("alapiToken");
                } else if (token instanceof String s) {
                    if (s.isEmpty()) {
                        currentHotItems.remove("alapiToken");
                    } else if (AnalysisSettingsService.isMasked(s)) {
                        // 掩码 = 不变
                    } else {
                        currentHotItems.put("alapiToken", s);
                    }
                }
            }
            result.put("hotItems", currentHotItems);
        }
        return result;
    }

    // ---------- JSON helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return defaultHomepageSettings();
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize settings", e);
        }
    }
}
