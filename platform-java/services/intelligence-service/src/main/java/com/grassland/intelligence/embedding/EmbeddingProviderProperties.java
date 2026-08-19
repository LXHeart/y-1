package com.grassland.intelligence.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.embedding")
public record EmbeddingProviderProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingsPath,
        Duration requestTimeout,
        int maxResponseBytes,
        int dimensions,
        boolean sendDimensions,
        int centsPer1kInputTokens) {

    public boolean sandbox() {
        return provider == null || provider.isBlank() || "sandbox".equalsIgnoreCase(provider.trim());
    }

    @Override
    public String toString() {
        return "EmbeddingProviderProperties[provider=" + provider + ", baseUrl=" + baseUrl
                + ", apiKey=[REDACTED], model=" + model
                + ", embeddingsPath=" + embeddingsPath
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + ", dimensions=" + dimensions
                + ", sendDimensions=" + sendDimensions
                + ", centsPer1kInputTokens=" + centsPer1kInputTokens + "]";
    }
}
