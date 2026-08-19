package com.grassland.intelligence.speech;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.speech")
public record SpeechProviderProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String transcriptionPath,
        Duration requestTimeout,
        int maxResponseBytes,
        int centsPer1kInputTokens,
        int centsPer1kOutputTokens,
        int centsPerSecond) {

    public boolean sandbox() {
        return provider == null || provider.isBlank() || "sandbox".equalsIgnoreCase(provider.trim());
    }

    @Override
    public String toString() {
        return "SpeechProviderProperties[provider=" + provider + ", baseUrl=" + baseUrl
                + ", apiKey=[REDACTED], model=" + model
                + ", transcriptionPath=" + transcriptionPath
                + ", requestTimeout=" + requestTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + ", centsPer1kInputTokens=" + centsPer1kInputTokens
                + ", centsPer1kOutputTokens=" + centsPer1kOutputTokens
                + ", centsPerSecond=" + centsPerSecond + "]";
    }
}
