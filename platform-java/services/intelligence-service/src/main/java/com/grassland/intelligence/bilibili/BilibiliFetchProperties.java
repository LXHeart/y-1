package com.grassland.intelligence.bilibili;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bilibili 页面 / WBI 抓取配置（移植 legacy {@code BILIBILI_USER_AGENT} / {@code BILIBILI_FETCH_TIMEOUT_MS}）。
 *
 * <p>默认值与 legacy {@code lib/env.ts} 一致：Chrome macOS UA、超时 15s。供 {@code BilibiliResolveService}
 * 抓取分享页 HTML 与 {@code playurl} WBI 接口。
 */
@ConfigurationProperties(prefix = "bilibili.fetch")
public record BilibiliFetchProperties(String userAgent, long timeoutMs) {

    /** 与 legacy {@code lib/env.ts} 默认 UA 一致。 */
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/123.0.0.0 Safari/537.36";

    public BilibiliFetchProperties {
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = DEFAULT_USER_AGENT;
        }
        timeoutMs = timeoutMs > 0 ? timeoutMs : 15_000;
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }
}
