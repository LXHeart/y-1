package com.grassland.intelligence.mediaplatform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * legacy Express 后端地址（草场 Slice 13 Stage 2）。用于 Bilibili DASH 等需 FFmpeg 的路径回落 legacy
 * （不在 WebFlux 请求线程里跑 FFmpeg）。默认容器内 {@code http://backend:3000}。
 */
@ConfigurationProperties(prefix = "legacy.backend")
public record LegacyMediaProxyProperties(String baseUrl) {

    public LegacyMediaProxyProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://backend:3000";
        } else {
            baseUrl = baseUrl.trim();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
    }
}
