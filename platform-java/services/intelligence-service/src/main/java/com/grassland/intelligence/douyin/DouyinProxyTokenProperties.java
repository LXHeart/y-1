package com.grassland.intelligence.douyin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 抖音代理 token 配置属性（对齐 legacy {@code DOUYIN_PROXY_TOKEN_SECRET}）。
 */
@Component
@ConfigurationProperties("douyin.proxy-token")
public record DouyinProxyTokenProperties(
        /** token 密钥，须 ≥32 字符（与 legacy 共享，安全起见不设默认值）。 */
        String tokenSecret,

        /** token 有效期（毫秒），默认 30 分钟（对齐 legacy）。 */
        Long tokenTtlMs) {

    public DouyinProxyTokenProperties {
        if (tokenTtlMs == null) {
            tokenTtlMs = 30L * 60 * 1000; // 30 minutes
        }
    }
}
