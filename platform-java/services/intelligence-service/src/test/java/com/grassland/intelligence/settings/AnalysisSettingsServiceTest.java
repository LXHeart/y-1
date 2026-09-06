package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 掩码 + 掩码感知 merge 的回归防护（GL: settings 迁移；任务书 #88 起 features 退役——fixture 全部
 * 迁到 feishu 形态，features 断言翻转为「不可见/忽略」）。
 *
 * <p>核心风险：前端 GET 到掩码值后整体 PUT 回来，若不识别掩码就会把真实密钥覆盖成 {@code "****abcd"}。
 */
class AnalysisSettingsServiceTest {

    @Test
    void maskKeepsLastFourAndFullyStarsShortSecrets() {
        assertThat(AnalysisSettingsService.maskSecret("sk-1234567890abcd")).isEqualTo("****abcd");
        assertThat(AnalysisSettingsService.maskSecret("abcd")).isEqualTo("****");
        assertThat(AnalysisSettingsService.maskSecret("ab")).isEqualTo("****");
    }

    @Test
    void isMaskedDetectsMaskPrefix() {
        assertThat(AnalysisSettingsService.isMasked("****abcd")).isTrue();
        assertThat(AnalysisSettingsService.isMasked("sk-real-key")).isFalse();
        assertThat(AnalysisSettingsService.isMasked(null)).isFalse();
    }

    @Test
    void maskedSecretRoundTripDoesNotOverwriteStoredKey() {
        Map<String, Object> current = settingsWithFeishuAppSecret("sk-1234567890abcd");
        // 前端把 GET 到的掩码值原样 PUT 回来
        Map<String, Object> partial = settingsWithFeishuAppSecret("****abcd");

        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(current, partial);

        assertThat(feishuAppSecret(merged)).isEqualTo("sk-1234567890abcd");
    }

    @Test
    void emptyStringClearsSecret() {
        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(
                settingsWithFeishuAppSecret("sk-1234567890abcd"), settingsWithFeishuAppSecret(""));

        assertThat(feishuAppSecret(merged)).isNull();
    }

    @Test
    void plaintextUpdatesSecret() {
        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(
                settingsWithFeishuAppSecret("sk-old"), settingsWithFeishuAppSecret("sk-new-9999"));

        assertThat(feishuAppSecret(merged)).isEqualTo("sk-new-9999");
    }

    @Test
    void nonSecretFieldsAreOverwritten() {
        Map<String, Object> current = settingsWithFeishu(mutable(Map.of("appId", "cli_a", "folderToken", "old")));
        Map<String, Object> partial = settingsWithFeishu(mutable(Map.of("folderToken", "new")));

        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(current, partial);

        Map<String, Object> feishu = feishu(merged);
        assertThat(feishu.get("folderToken")).isEqualTo("new");
        // 未提交的字段保留
        assertThat(feishu.get("appId")).isEqualTo("cli_a");
    }

    @Test
    void mergeIgnoresRequestFeatures() {
        // 任务书 #88：请求体 features 整键忽略——merged 无 features 且 feishu 不受影响
        Map<String, Object> current = settingsWithFeishu(mutable(Map.of("appId", "cli_keep")));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("features", mutable(Map.of("video", mutable(Map.of("apiKey", "sk-x")))));
        partial.put("integrations", mutable(Map.of("feishu", mutable(Map.of("appId", "cli_new")))));

        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(current, partial);

        assertThat(merged).doesNotContainKey("features");
        assertThat(feishu(merged).get("appId")).isEqualTo("cli_new");
    }

    // ---------- schema 守卫（settings 存量 jsonb 归一；#88 起 features 出白名单） ----------

    @Test
    void normalizeDropsFeaturesUnknownKeysAndNonStringValues() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("features", mutable(Map.of(
                "video", mutable(Map.of(
                        "provider", "qwen", "baseUrl", "https://api.example.com/v1",
                        "evilKey", "junk", "blankKey", "  ", "numberKey", 42)),
                "unknownFeature", mutable(Map.of("apiKey", "sk-x")))));
        legacy.put("integrations", mutable(Map.of(
                "feishu", mutable(Map.of("appId", "cli_x", "extra", "junk")),
                "notFeishu", Map.of("a", "b"))));
        legacy.put("topLevelJunk", "drop me");

        Map<String, Object> normalized = SettingsSchemaGuard.normalize("analysis", legacy);

        // 任务书 #88：normalize 后无 features 键（API 双向不可见）
        assertThat(normalized).doesNotContainKey("features");
        assertThat(normalized).doesNotContainKey("topLevelJunk");
        @SuppressWarnings("unchecked")
        Map<String, Object> feishu = (Map<String, Object>) ((Map<String, Object>) normalized.get("integrations")).get("feishu");
        assertThat(feishu).containsOnlyKeys("appId");
    }

    @Test
    void validateIgnoresLegacyFeaturesButStillRejectsBadFeishu() {
        // features 坏值（枚举外 provider/私网 baseUrl/超长 apiKey/数字值）不再抛——normalize 丢弃后无从校验
        Map<String, Object> legacyFeatures = new LinkedHashMap<>();
        legacyFeatures.put("features", mutable(Map.of(
                "video", mutable(Map.of(
                        "provider", "openai",
                        "baseUrl", "http://127.0.0.1:8080/v1",
                        "apiKey", "x".repeat(513),
                        "numberKey", 42)))));
        Map<String, Object> normalized = SettingsSchemaGuard.normalize("analysis", legacyFeatures);
        assertThatCode(() -> SettingsSchemaGuard.validate("analysis", normalized, "{}"))
                .doesNotThrowAnyException();

        // feishu 超长 appId 仍 400（保留语义回归）
        Map<String, Object> badFeishu = settingsWithFeishu(mutable(Map.of("appId", "x".repeat(257))));
        assertThatThrownBy(() -> SettingsSchemaGuard.validate(
                        "analysis", SettingsSchemaGuard.normalize("analysis", badFeishu), "{}"))
                .isInstanceOf(com.grassland.intelligence.security.IntelligenceException.class)
                .hasMessageContaining("过长");

        // homepage 分支断言保留（既有语义不动）
        Map<String, Object> homepage = new LinkedHashMap<>();
        homepage.put("hotItems", mutable(Map.of("provider", "bogus")));
        assertThatThrownBy(() -> SettingsSchemaGuard.validate(
                        "homepage", SettingsSchemaGuard.normalize("homepage", homepage), "{}"))
                .hasMessageContaining("provider");
    }

    // ---------- helpers ----------

    private static Map<String, Object> settingsWithFeishuAppSecret(String appSecret) {
        return settingsWithFeishu(mutable(Map.of("appSecret", appSecret)));
    }

    private static Map<String, Object> settingsWithFeishu(Map<String, Object> feishuFields) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("integrations", mutable(Map.of("feishu", feishuFields)));
        return settings;
    }

    private static String feishuAppSecret(Map<String, Object> settings) {
        Object value = feishu(settings).get("appSecret");
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> feishu(Map<String, Object> settings) {
        Map<String, Object> integrations = (Map<String, Object>) settings.get("integrations");
        return (Map<String, Object>) integrations.get("feishu");
    }

    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }
}
