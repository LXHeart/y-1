package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformModelControlPlaneService")
class PlatformModelControlPlaneServiceTest {

    @Mock
    PlatformModelConfigRepository repository;
    @InjectMocks
    PlatformModelControlPlaneService service;

    @Test
    @DisplayName("primary 与 backup 都不可用时不返回平台模型")
    void excludesUnhealthyModels() {
        when(repository.findCurrentByCapability("text")).thenReturn(Flux.just(
                config("primary", "unhealthy", 2),
                config("backup", "unhealthy", 1)));

        assertThat(service.resolve("text").block()).isEmpty();
    }

    @Test
    @DisplayName("解析结果保留运行时并发上限")
    void keepsConcurrencyLimit() {
        when(repository.findCurrentByCapability("text")).thenReturn(Flux.just(
                config("primary", "healthy", 3)));

        assertThat(service.resolve("text").block()).get()
                .satisfies(resolved -> {
                    assertThat(resolved.maxConcurrency()).isEqualTo(3);
                    assertThat(resolved.configId()).isNotNull();
                });
    }

    private static PlatformModelConfig config(String role, String health, int maxConcurrency) {
        return new PlatformModelConfig(UUID.randomUUID(), "text", role, "qwen", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", maxConcurrency,
                health, true, 1, "admin", null, null);
    }
}
