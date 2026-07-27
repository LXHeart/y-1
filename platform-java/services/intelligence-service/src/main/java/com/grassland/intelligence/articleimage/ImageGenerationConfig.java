package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.ProviderUrlGuard;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 平台图片生成 provider 配置；未单独配置时回落平台 Qwen，仍不读取用户 BYOK。 */
@Component
public class ImageGenerationConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ImageGenerationConfig(Environment env) {
        this.baseUrl = firstNonBlank(
                env.getProperty("ai.image-generation.base-url"),
                env.getProperty("ai.qwen.base-url"));
        this.apiKey = firstNonBlank(
                env.getProperty("ai.image-generation.api-key"),
                env.getProperty("ai.qwen.api-key"));
        this.model = firstNonBlank(
                env.getProperty("ai.image-generation.model"),
                env.getProperty("ai.qwen.model", "qwen-plus"));
        this.connectTimeout = Duration.ofMillis(env.getProperty(
                "ai.image-generation.connect-timeout-ms", Long.class, 5_000L));
        this.readTimeout = Duration.ofMillis(env.getProperty(
                "ai.image-generation.read-timeout-ms", Long.class, 300_000L));
    }

    @PostConstruct
    void validate() {
        if (baseUrl == null || apiKey == null || model == null) {
            throw new IllegalStateException("intelligence-service 需要配置图片生成 provider");
        }
        ProviderUrlGuard.validate(baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }
}
