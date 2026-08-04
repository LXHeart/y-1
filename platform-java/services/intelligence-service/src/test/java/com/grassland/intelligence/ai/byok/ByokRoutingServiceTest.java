package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * {@link ByokRoutingService} fallback 授权矩阵（HLD §12.3 硬规则）：BYOK 优先；无 BYOK 时按
 * {@code allowFallback} 决定回落平台或拒绝——绝不静默扣平台额度。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ByokRoutingService (fallback 授权矩阵)")
class ByokRoutingServiceTest {

    @Mock
    AiProviderKeyRepository keyRepository;
    @Mock
    PlatformModelControlPlaneService platformModelControlPlane;
    @InjectMocks
    ByokRoutingService service;

    @Test
    @DisplayName("组织 BYOK 存在 → BYOK 解析（回传密文，解密不在本层）")
    void orgByokPresent() {
        AiProviderKey key = new AiProviderKey(UUID.randomUUID(), "org", "acct", "text",
                "openai-compatible", "http://host", "byok-model", "ciphertext", "v1", "sk-***", true, null, null);
        when(keyRepository.findByOrganizationAndCapability("org", "text")).thenReturn(Mono.just(key));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", false).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.needsKeyDecryption()).isTrue();
        assertThat(r.encryptedKey()).isEqualTo("ciphertext");
        assertThat(r.chargesPlatformFee()).isFalse();
    }

    @Test
    @DisplayName("无 BYOK + allowFallback=true + 平台配置存在 → PLATFORM")
    void noByokFallbackToPlatform() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("text")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel("qwen", "qwen-plus", "http://host", 1, "primary"))));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isPlatform()).isTrue();
        assertThat(r.chargesPlatformFee()).isTrue();
        assertThat(r.platformModelVersion()).isEqualTo(1);
        assertThat(r.model()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("无 BYOK + allowFallback=false → DENIED(fallback_not_authorized)，不扣平台额度")
    void noByokFallbackUnauthorized() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());

        ProviderResolution r = service.resolveProvider(null, "acct", "text", false).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("fallback_not_authorized");
    }

    @Test
    @DisplayName("无 BYOK + allowFallback=true + 无平台配置 → DENIED(no_platform_model)")
    void noByokNoPlatformModel() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("text")).thenReturn(Mono.just(Optional.empty()));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("no_platform_model");
    }
}
