package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 掩码 + 掩码感知 merge 的回归防护（GL: settings 迁移）。
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
        Map<String, Object> current = settingsWithVideoApiKey("sk-1234567890abcd");
        // 前端把 GET 到的掩码值原样 PUT 回来
        Map<String, Object> partial = settingsWithVideoApiKey("****abcd");

        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(current, partial);

        assertThat(videoApiKey(merged)).isEqualTo("sk-1234567890abcd");
    }

    @Test
    void emptyStringClearsSecret() {
        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(
                settingsWithVideoApiKey("sk-1234567890abcd"), settingsWithVideoApiKey(""));

        assertThat(videoApiKey(merged)).isNull();
    }

    @Test
    void plaintextUpdatesSecret() {
        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(
                settingsWithVideoApiKey("sk-old"), settingsWithVideoApiKey("sk-new-9999"));

        assertThat(videoApiKey(merged)).isEqualTo("sk-new-9999");
    }

    @Test
    void nonSecretFieldsAreOverwritten() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("features", mutable(Map.of("video", mutable(Map.of("provider", "qwen", "model", "old")))));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("features", mutable(Map.of("video", mutable(Map.of("model", "new")))));

        Map<String, Object> merged = AnalysisSettingsService.mergeAnalysisSettings(current, partial);

        Map<String, Object> video = feature(merged, "video");
        assertThat(video.get("model")).isEqualTo("new");
        // 未提交的字段保留
        assertThat(video.get("provider")).isEqualTo("qwen");
    }

    // ---------- helpers ----------

    private static Map<String, Object> settingsWithVideoApiKey(String apiKey) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("provider", "qwen");
        video.put("apiKey", apiKey);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("video", video);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("features", features);
        return settings;
    }

    private static String videoApiKey(Map<String, Object> settings) {
        Object value = feature(settings, "video").get("apiKey");
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> feature(Map<String, Object> settings, String name) {
        Map<String, Object> features = (Map<String, Object>) settings.get("features");
        return (Map<String, Object>) features.get(name);
    }

    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }
}
