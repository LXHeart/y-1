package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VideoGenerationProviderRegistry {

    private final VideoGenerationProperties properties;
    private final List<VideoGenerationProvider> providers;

    public VideoGenerationProviderRegistry(
            VideoGenerationProperties properties, List<VideoGenerationProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    public VideoGenerationProvider active() {
        if (!properties.available()) {
            throw new IntelligenceException(503, properties.unavailableReason());
        }
        return providers.stream()
                .filter(provider -> provider.id().equalsIgnoreCase(properties.getMode()))
                .findFirst()
                .orElseThrow(() -> new IntelligenceException(503, "视频 provider adapter 未装配"));
    }

    public VideoGenerationProvider require(String providerId) {
        return providers.stream()
                .filter(provider -> provider.id().equalsIgnoreCase(providerId))
                .findFirst()
                .orElseThrow(() -> new IntelligenceException(503, "视频 provider adapter 未装配"));
    }
}
