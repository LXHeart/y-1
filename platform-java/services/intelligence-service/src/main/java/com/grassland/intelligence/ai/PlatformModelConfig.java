package com.grassland.intelligence.ai;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 平台默认 AI 模型配置（HLD §12.3 平台默认配置的第一步）。从环境变量读 Qwen base-url/api-key/model。
 *
 * <p>intelligence **不读 legacy per-user 设置**——所有用户统一用平台默认 Qwen（迈向 HLD §12.3 平台默认）。
 * BYOK / 能力路由 / 预算 / 健康检查（model-control-plane 全貌）留后续 slice（卡未决的 D-11 成本归属）。
 * 启动期 {@link #validate} 校验 base-url/api-key 非空 + SSRF 防护；缺失或非法即 fail-fast。
 */
@Component
public class PlatformModelConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public PlatformModelConfig(Environment env) {
        this.baseUrl = env.getProperty("ai.qwen.base-url", "");
        this.apiKey = env.getProperty("ai.qwen.api-key", "");
        this.model = env.getProperty("ai.qwen.model", "qwen-plus");
        this.connectTimeout = Duration.ofMillis(env.getProperty("ai.qwen.connect-timeout-ms", Long.class, 5000L));
        this.readTimeout = Duration.ofMillis(env.getProperty("ai.qwen.read-timeout-ms", Long.class, 120000L));
    }

    @PostConstruct
    void validate() {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw new IllegalStateException("intelligence-service 需要配置 ai.qwen.base-url 与 ai.qwen.api-key");
        }
        ProviderUrlGuard.validate(baseUrl);
    }

    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
}
