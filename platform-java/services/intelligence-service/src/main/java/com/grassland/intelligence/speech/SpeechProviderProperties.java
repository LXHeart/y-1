package com.grassland.intelligence.speech;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Speech 适配器功能参数（任务书 #58 决策 H：模型层配置——provider/base-url/api-key/model——
 * 全部迁入控制面 {@code platform_model_config} + 凭据，本类只剩适配器行为参数与 sandbox 常量）。
 *
 * <p>原 {@code AiCapabilityProviderConfigValidator} 的范围校验并入本构造器（S2.3）。
 */
@ConfigurationProperties(prefix = "ai.speech")
public record SpeechProviderProperties(
        String transcriptionPath,
        Duration requestTimeout,
        int maxResponseBytes) {

    /** 内置 Sandbox 平台解析的假模型名（决策 F：控制面无行且 allow-sandbox=true 时使用）。 */
    public static final String SANDBOX_MODEL = "sandbox-speech-v1";

    public SpeechProviderProperties {
        if (transcriptionPath == null || !transcriptionPath.startsWith("/") || transcriptionPath.startsWith("//")
                || transcriptionPath.contains("..") || transcriptionPath.contains("?")
                || transcriptionPath.contains("#")) {
            throw new IllegalStateException("Speech Provider path 必须是无查询参数的绝对路径");
        }
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("Speech requestTimeout 必须在 1 秒到 5 分钟之间");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalStateException("Speech maxResponseBytes 必须在 1 KiB 到 16 MiB 之间");
        }
    }

    @Override
    public String toString() {
        return "SpeechProviderProperties[transcriptionPath=" + transcriptionPath
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }
}
