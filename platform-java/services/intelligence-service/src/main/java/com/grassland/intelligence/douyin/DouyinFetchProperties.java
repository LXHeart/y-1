package com.grassland.intelligence.douyin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 抖音页面抓取配置（移植 legacy env：{@code DOUYIN_USER_AGENT} / {@code DOUYIN_COOKIE_USER_AGENT} /
 * {@code DOUYIN_FETCH_TIMEOUT_MS}）。
 *
 * <p>桌面页阶段用 {@code cookieUserAgent}（未配回落 {@code userAgent}），移动分享页阶段用
 * {@code MOBILE_SHARE_USER_AGENT} 硬编码（与 legacy {@code mobileShareUserAgent} 逐字一致）。
 */
@ConfigurationProperties(prefix = "douyin.fetch")
public record DouyinFetchProperties(String userAgent, String cookieUserAgent, long timeoutMs, int maxRedirects) {

    /** 与 legacy {@code lib/env.ts} 的 {@code DOUYIN_USER_AGENT} 默认值一致。 */
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/123.0.0.0 Safari/537.36";

    /** 与 legacy {@code douyin-resolve.service.ts} 的 {@code mobileShareUserAgent} 逐字一致。 */
    public static final String MOBILE_SHARE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

    public DouyinFetchProperties {
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = DEFAULT_USER_AGENT;
        }
        if (cookieUserAgent == null || cookieUserAgent.isBlank()) {
            cookieUserAgent = userAgent;
        }
        timeoutMs = timeoutMs > 0 ? timeoutMs : 15_000;
        maxRedirects = maxRedirects > 0 ? maxRedirects : 5;
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }
}
