package com.grassland.intelligence.bilibili;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bilibili 代理 token 配置（移植 legacy {@code BILIBILI_PROXY_TOKEN_SECRET} + {@code proxyTokenTtlMs}）。
 *
 * <p>{@code token-secret} 与 legacy Express **共享同一值**：intelligence 签发、legacy 在 DASH 逃生口验签 + mux；
 * 缺失/过短时 create/parse 抛错（懒校验，避免拖垮未配置环境的上下文装配）。默认 TTL 15 分钟。
 */
@ConfigurationProperties(prefix = "bilibili.proxy")
public record BilibiliProxyTokenProperties(String tokenSecret, long tokenTtlMs) {

    public BilibiliProxyTokenProperties {
        tokenTtlMs = tokenTtlMs > 0 ? tokenTtlMs : 900_000L;
    }
}
