package com.grassland.intelligence.douyin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 抖音解析配置属性（对齐 legacy fetch 超时配置）。
 */
@Component
@ConfigurationProperties("douyin.fetch")
public record DouyinFetchProperties(
        /** 页面请求超时（毫秒），默认 10 秒。 */
        Long pageTimeoutMs,

        /** 视频流请求超时（毫秒），默认 30 秒。 */
        Long videoTimeoutMs,

        /** 最大重定向次数，默认 5。 */
        Integer maxRedirects) {

    public DouyinFetchProperties {
        if (pageTimeoutMs == null) {
            pageTimeoutMs = 10000L;
        }
        if (videoTimeoutMs == null) {
            videoTimeoutMs = 30000L;
        }
        if (maxRedirects == null) {
            maxRedirects = 5;
        }
    }
}
