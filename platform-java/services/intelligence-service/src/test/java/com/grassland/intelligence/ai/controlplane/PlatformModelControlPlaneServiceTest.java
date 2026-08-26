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
        when(repository.findCurrentWithCredentialByCapability("text")).thenReturn(Flux.just(
                withoutCredential(config("primary", "unhealthy", 2)),
                withoutCredential(config("backup", "unhealthy", 1))));

        assertThat(service.resolve("text").block()).isEmpty();
    }

    @Test
    @DisplayName("解析结果保留运行时并发上限")
    void keepsConcurrencyLimit() {
        when(repository.findCurrentWithCredentialByCapability("text")).thenReturn(Flux.just(
                withoutCredential(config("primary", "healthy", 3))));

        assertThat(service.resolve("text").block()).get()
                .satisfies(resolved -> {
                    assertThat(resolved.maxConcurrency()).isEqualTo(3);
                    assertThat(resolved.configId()).isNotNull();
                });
    }

    /** 任务书 #47 S2：凭据是目的地真相源——baseUrl 取凭据而非配置列，密文原样下传交执行层解密。 */
    @Test
    @DisplayName("有凭据时 baseUrl 取凭据、密文与版本随解析结果下传")
    void prefersCredentialDestinationAndCarriesCiphertext() {
        PlatformModelConfig config = config("primary", "healthy", 4);
        when(repository.findCurrentWithCredentialByCapability("text")).thenReturn(Flux.just(
                new PlatformModelWithCredential(config, UUID.randomUUID(),
                        "https://credential.example/v1", "synthetic-ciphertext", 7L)));

        assertThat(service.resolve("text").block()).get()
                .satisfies(resolved -> {
                    assertThat(resolved.baseUrl()).isEqualTo("https://credential.example/v1");
                    assertThat(resolved.credentialEncryptedKey()).isEqualTo("synthetic-ciphertext");
                    assertThat(resolved.credentialVersion()).isEqualTo(7L);
                    assertThat(resolved.credentialId()).isNotNull();
                });
    }

    /** 无凭据（V47 收 NOT NULL 前的过渡态）：回落配置列 base_url，密钥交 env 兜底。 */
    @Test
    @DisplayName("无凭据时回落配置列 baseUrl，密文为空")
    void fallsBackToConfigBaseUrlWithoutCredential() {
        when(repository.findCurrentWithCredentialByCapability("text")).thenReturn(Flux.just(
                withoutCredential(config("primary", "healthy", 1))));

        assertThat(service.resolve("text").block()).get()
                .satisfies(resolved -> {
                    assertThat(resolved.baseUrl())
                            .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
                    assertThat(resolved.credentialEncryptedKey()).isNull();
                });
    }

    private static PlatformModelWithCredential withoutCredential(PlatformModelConfig config) {
        return new PlatformModelWithCredential(config, null, null, null, null);
    }

    private static PlatformModelConfig config(String role, String health, int maxConcurrency) {
        return new PlatformModelConfig(UUID.randomUUID(), "text", role, "qwen", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", maxConcurrency,
                health, true, 1, "admin", null, null);
    }
}
