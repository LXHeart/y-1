package com.grassland.intelligence.douyin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 抖音代理 token 配置（移植 legacy {@code DOUYIN_PROXY_TOKEN_SECRET} + {@code proxyTokenTtlMs}）。
 *
 * <p>{@code token-secret} 与 legacy Express **共享同一值**：两侧各自签发/验签（Java extract 签发，
 * legacy {@code /api/douyin/audio} 等未迁端点验签），缺失/过短时 create/parse 抛错（懒校验，
 * 避免拖垮未配置环境的上下文装配）。默认 TTL 15 分钟（与 legacy {@code proxyTokenTtlMs} 一致）。
 */
@ConfigurationProperties(prefix = "douyin.proxy")
public record DouyinProxyTokenProperties(String tokenSecret, long tokenTtlMs) {

    public DouyinProxyTokenProperties {
        tokenTtlMs = tokenTtlMs > 0 ? tokenTtlMs : 900_000L;
    }
}
