package com.grassland.intelligence.douyin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 抖音热点配置（移植 legacy env：{@code DOUYIN_HOT_API_BASE_URL} / {@code DOUYIN_HOT_API_TIMEOUT_MS}）。
 *
 * <p>默认值与 legacy {@code lib/env.ts} 一致：上游 {@code https://60s.viki.moe/v2/douyin}，超时 8000ms，取前 10 条。
 */
@ConfigurationProperties(prefix = "douyin.hot")
public record DouyinHotItemsProperties(String apiBaseUrl, long apiTimeoutMs, int limit) {

    public DouyinHotItemsProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://60s.viki.moe/v2/douyin";
        }
        apiTimeoutMs = apiTimeoutMs > 0 ? apiTimeoutMs : 8_000;
        limit = limit > 0 ? limit : 10;
    }

    public Duration apiTimeout() {
        return Duration.ofMillis(apiTimeoutMs);
    }
}
