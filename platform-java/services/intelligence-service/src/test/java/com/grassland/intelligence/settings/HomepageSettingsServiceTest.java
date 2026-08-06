package com.grassland.intelligence.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 首页设置掩码语义回归（alapiToken 与 analysis 密钥同口径）。 */
class HomepageSettingsServiceTest {

    @Test
    void defaultProviderIs60s() {
        assertThat(hotItems(HomepageSettingsService.defaultHomepageSettings()).get("provider")).isEqualTo("60s");
    }

    @Test
    void maskedTokenRoundTripKeepsStoredToken() {
        Map<String, Object> current = settings("alapi", "tok-1234567890wxyz");
        Map<String, Object> merged = HomepageSettingsService.mergeHomepageSettings(
                current, settings("alapi", "****wxyz"));

        assertThat(hotItems(merged).get("alapiToken")).isEqualTo("tok-1234567890wxyz");
    }

    @Test
    void emptyStringClearsToken() {
        Map<String, Object> merged = HomepageSettingsService.mergeHomepageSettings(
                settings("alapi", "tok-1234567890wxyz"), settings("alapi", ""));

        assertThat(hotItems(merged)).doesNotContainKey("alapiToken");
    }

    @Test
    void providerIsOverwritten() {
        Map<String, Object> merged = HomepageSettingsService.mergeHomepageSettings(
                settings("60s", null), settings("alapi", null));

        assertThat(hotItems(merged).get("provider")).isEqualTo("alapi");
    }

    @Test
    void getMasksTokenSoPlaintextNeverLeavesService() {
        Map<String, Object> masked = HomepageSettingsService.maskAlapiToken(settings("alapi", "tok-1234567890wxyz"));

        assertThat(hotItems(masked).get("alapiToken")).isEqualTo("****wxyz");
    }

    private static Map<String, Object> settings(String provider, String token) {
        Map<String, Object> hot = new LinkedHashMap<>();
        hot.put("provider", provider);
        if (token != null) {
            hot.put("alapiToken", token);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("hotItems", hot);
        return settings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hotItems(Map<String, Object> settings) {
        return (Map<String, Object>) settings.get("hotItems");
    }
}
