package com.grassland.intelligence.settings;

import com.grassland.intelligence.ai.ProviderUrlGuard;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * user_settings jsonb 的 schema 守卫（settings 存量 jsonb 归一/数据治理，2026-08-16）。
 *
 * <p>legacy 时代 settings_json 是无 schema 自由 jsonb：{@code mergeFeature} 会照抄任意未知键，
 * 值无类型/长度约束，整包无大小上限。本守卫按前端契约（types/settings.ts）定义白名单：
 * <ul>
 *   <li><b>未知键 → 静默丢弃</b>（客户端版本偏斜不该 500/400）；</li>
 *   <li><b>已知键的坏值 → 400</b>（类型错、超长）——契约违规要响亮失败，不静默截断；</li>
 *   <li>整包序列化上限 {@value MAX_SERIALIZED_BYTES} 字节。</li>
 * </ul>
 *
 * <p>analysis 类型白名单（任务书 #88 起）：仅 {@code integrations.feishu.{appId,appSecret,folderToken}}——
 * {@code features.*} 已退役（normalize 直接丢弃、validate 不再校验；请求携带 features 由
 * {@code AnalysisSettingsService.update} 整键忽略，存量存储 preserve-on-write 另见其 javadoc）。
 *
 * <p>读路径也过 {@link #normalize}：存量 legacy 脏数据（未知键/越界值）在响应里永远是规整后的形态。
 * 密钥字段（apiKey/apiToken/appSecret/alapiToken）的掩码语义在 merge 层处理，本守卫只约束长度与类型。
 */
final class SettingsSchemaGuard {

    static final int MAX_SERIALIZED_BYTES = 8192;

    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_SECRET_LENGTH = 512;
    private static final int MAX_FEISHU_ID_LENGTH = 256;

    private SettingsSchemaGuard() {}

    /** 白名单归一：只保留已知 section/键；丢弃值为空白串的键（等价未设置）。 */
    static Map<String, Object> normalize(String settingsType, Map<String, Object> settings) {
        if ("homepage".equals(settingsType)) {
            return normalizeHomepage(settings);
        }
        return normalizeAnalysis(settings);
    }

    /** 校验已知键的值并强制整包大小上限；违规抛 400（用于写入路径，merge 之后）。 */
    static void validate(String settingsType, Map<String, Object> settings, String serialized) {
        if (serialized.length() > MAX_SERIALIZED_BYTES) {
            throw new IntelligenceException(400, "设置内容过大（上限 " + MAX_SERIALIZED_BYTES + " 字节）");
        }
        if ("homepage".equals(settingsType)) {
            validateHomepage(settings);
        } else {
            validateAnalysis(settings);
        }
    }

    // ---------- analysis ----------

    private static Map<String, Object> normalizeAnalysis(Map<String, Object> settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object integrationsObj = settings == null ? null : settings.get("integrations");
        if (integrationsObj instanceof Map<?, ?> integrations
                && integrations.get("feishu") instanceof Map<?, ?> feishu) {
            Map<String, Object> normalizedFeishu = keepKnownKeys(
                    asMap(feishu), Set.of("appId", "appSecret", "folderToken"));
            if (!normalizedFeishu.isEmpty()) {
                result.put("integrations", Map.of("feishu", normalizedFeishu));
            }
        }
        return result;
    }

    private static void validateAnalysis(Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        if (settings.get("integrations") instanceof Map<?, ?> integrations
                && integrations.get("feishu") instanceof Map<?, ?> raw) {
            Map<?, ?> feishu = asMap(raw);
            requireStringLength(feishu, "appId", MAX_FEISHU_ID_LENGTH);
            requireStringLength(feishu, "appSecret", MAX_SECRET_LENGTH);
            requireStringLength(feishu, "folderToken", MAX_FEISHU_ID_LENGTH);
        }
    }

    // ---------- homepage ----------

    private static Map<String, Object> normalizeHomepage(Map<String, Object> settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (settings != null && settings.get("hotItems") instanceof Map<?, ?> raw) {
            Map<String, Object> hotItems = keepKnownKeys(asMap(raw), Set.of("provider", "alapiToken"));
            if (!hotItems.isEmpty()) {
                result.put("hotItems", hotItems);
            }
        }
        return result;
    }

    private static void validateHomepage(Map<String, Object> settings) {
        if (settings != null && settings.get("hotItems") instanceof Map<?, ?> raw) {
            Map<?, ?> hotItems = asMap(raw);
            requireEnum(hotItems, "provider", Set.of("60s", "alapi"));
            requireStringLength(hotItems, "alapiToken", MAX_SECRET_LENGTH);
        }
    }

    // ---------- 通用 ----------

    /** 只保留白名单内的字符串键；空白串值视为未设置（剔除）。 */
    private static Map<String, Object> keepKnownKeys(Map<?, ?> source, Set<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (String key : allowed) {
            Object value = source.get(key);
            if (value instanceof String s && !s.isBlank()) {
                result.put(key, s);
            }
        }
        return result;
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static void requireEnum(Map<?, ?> section, String key, Set<String> allowed) {
        Object value = section.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String s) || !allowed.contains(s)) {
            throw new IntelligenceException(400, key + " 取值无效（允许：" + String.join("/", allowed) + "）");
        }
    }

    private static void requireStringLength(Map<?, ?> section, String key, int max) {
        Object value = section.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String s)) {
            throw new IntelligenceException(400, key + " 必须是字符串");
        }
        if (s.length() > max) {
            throw new IntelligenceException(400, key + " 过长（上限 " + max + " 字符）");
        }
    }

    /** baseUrl 结构校验：http/https、无凭据、有主机、非私网字面量（ProviderUrlGuard 同口径）。 */
    private static void requireUrl(Map<?, ?> section, String key) {
        Object value = section.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String s)) {
            throw new IntelligenceException(400, key + " 必须是字符串");
        }
        if (s.length() > MAX_URL_LENGTH) {
            throw new IntelligenceException(400, key + " 过长（上限 " + MAX_URL_LENGTH + " 字符）");
        }
        try {
            ProviderUrlGuard.validate(s);
        } catch (IllegalArgumentException error) {
            throw new IntelligenceException(400, error.getMessage());
        }
    }
}
