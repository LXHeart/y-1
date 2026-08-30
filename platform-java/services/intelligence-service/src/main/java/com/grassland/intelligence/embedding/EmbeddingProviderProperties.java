package com.grassland.intelligence.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 适配器功能参数（任务书 #58 决策 H：模型层配置——provider/base-url/api-key/model——
 * 全部迁入控制面 {@code platform_model_config} + 凭据，本类只剩适配器行为参数与 sandbox 常量）。
 *
 * <p>原 {@code AiCapabilityProviderConfigValidator} 的范围校验并入本构造器（S2.3）。
 * {@code dimensions} 是索引/查询可比性的全局约定（向量列宽），不随模型行变化。
 */
@ConfigurationProperties(prefix = "ai.embedding")
public record EmbeddingProviderProperties(
        String embeddingsPath,
        Duration requestTimeout,
        int maxResponseBytes,
        int dimensions,
        boolean sendDimensions) {

    /** 内置 Sandbox 平台解析的假模型名（决策 F：控制面无行且 allow-sandbox=true 时使用）。 */
    public static final String SANDBOX_MODEL = "sandbox-embedding-v1";

    public EmbeddingProviderProperties {
        if (embeddingsPath == null || !embeddingsPath.startsWith("/") || embeddingsPath.startsWith("//")
                || embeddingsPath.contains("..") || embeddingsPath.contains("?")
                || embeddingsPath.contains("#")) {
            throw new IllegalStateException("Embedding Provider path 必须是无查询参数的绝对路径");
        }
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("Embedding requestTimeout 必须在 1 秒到 5 分钟之间");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalStateException("Embedding maxResponseBytes 必须在 1 KiB 到 16 MiB 之间");
        }
        if (dimensions < 1 || dimensions > 4096) {
            throw new IllegalStateException("Embedding dimensions 必须在 1-4096 之间");
        }
    }

    @Override
    public String toString() {
        return "EmbeddingProviderProperties[embeddingsPath=" + embeddingsPath
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + ", dimensions=" + dimensions
                + ", sendDimensions=" + sendDimensions + "]";
    }
}
