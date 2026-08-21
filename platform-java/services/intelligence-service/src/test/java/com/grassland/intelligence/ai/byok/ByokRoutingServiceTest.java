package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
 * {@link ByokRoutingService} 路由矩阵（HLD §12.3 / ADR-D17）：
 * 个人 BYOK &gt; 组织 BYOK &gt; 平台模型；回退授权按组织是否配置组织密钥分两档——
 * 未配置沿用调用方 allowFallback；配置后须组织策略 + allowFallback 双满足。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ByokRoutingService (个人/组织/平台路由矩阵)")
class ByokRoutingServiceTest {

    @Mock
    AiProviderKeyRepository keyRepository;
    @Mock
    AiOrgByokPolicyRepository policyRepository;
    @Mock
    PlatformModelControlPlaneService platformModelControlPlane;
    @InjectMocks
    ByokRoutingService service;

    private static AiProviderKey key(String organizationId, String keyVersion) {
        return new AiProviderKey(UUID.randomUUID(), organizationId, "acct", "text",
                "openai-compatible", "http://host", "byok-model", "ciphertext", keyVersion, "sk-***", true,
                null, null);
    }

    private static AiOrgByokPolicy policy(boolean allowFallback) {
        return new AiOrgByokPolicy("org", allowFallback, 1, "admin-acct", null);
    }

    @Test
    @DisplayName("个人 BYOK 优先于组织密钥——命中个人时不查组织层")
    void personalByokWinsOverOrgKey() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.just(key(null, "v1")));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", false).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.needsKeyDecryption()).isTrue();
        assertThat(r.encryptedKey()).isEqualTo("ciphertext");
        assertThat(r.modelVersionKey()).isEqualTo("byok:v1");
        assertThat(r.byokOrganizationId()).isNull();
        assertThat(r.chargesPlatformFee()).isFalse();
        verify(keyRepository).findByPersonalAndCapability("acct", "text");
        verifyNoMoreInteractions(keyRepository);
    }

    @Test
    @DisplayName("无个人密钥时组织密钥兜底——byokOrganizationId 带组织 ID，modelVersionKey 用 byok-org 前缀")
    void orgKeyUsedWhenNoPersonalKey() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(keyRepository.findByOrganizationAndCapability("org", "text")).thenReturn(Mono.just(key("org", "v2")));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", false).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.byokOrganizationId()).isEqualTo("org");
        assertThat(r.modelVersionKey()).isEqualTo("byok-org:v2");
        assertThat(r.chargesPlatformFee()).isFalse();
    }

    @Test
    @DisplayName("组织配了组织密钥：无该能力组织密钥且策略默认（无行）→ DENIED，不静默扣平台额度")
    void orgWithKeysButPolicyAbsentDeniesFallback() {
        when(keyRepository.findByPersonalAndCapability("acct", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.findByOrganizationAndCapability("org", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(true));
        when(policyRepository.find("org")).thenReturn(Mono.empty());

        ProviderResolution r = service.resolveProvider("org", "acct", "image_generation", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("fallback_not_authorized");
        verifyNoMoreInteractions(platformModelControlPlane);
    }

    @Test
    @DisplayName("组织配了组织密钥：策略显式允许 + 调用方授权 → 回退平台")
    void orgPolicyAndRequestBothAllowFallbackToPlatform() {
        when(keyRepository.findByPersonalAndCapability("acct", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.findByOrganizationAndCapability("org", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(true));
        when(policyRepository.find("org")).thenReturn(Mono.just(policy(true)));
        when(platformModelControlPlane.resolve("image_generation")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel(
                        UUID.randomUUID(), "qwen", "qwen-plus", "http://host", 1, "primary", 4))));

        ProviderResolution r = service.resolveProvider("org", "acct", "image_generation", true).block();

        assertThat(r.isPlatform()).isTrue();
        assertThat(r.chargesPlatformFee()).isTrue();
    }

    @Test
    @DisplayName("组织配了组织密钥：策略允许但调用方未授权 → 仍 DENIED（双满足缺一不可）")
    void orgPolicyAllowsButRequestDoesNot() {
        when(keyRepository.findByPersonalAndCapability("acct", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.findByOrganizationAndCapability("org", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(true));
        when(policyRepository.find("org")).thenReturn(Mono.just(policy(true)));

        ProviderResolution r = service.resolveProvider("org", "acct", "image_generation", false).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("fallback_not_authorized");
    }

    @Test
    @DisplayName("组织未配任何组织密钥：回退沿用调用方 allowFallback（与组织级开启前一致）")
    void orgWithoutOrgKeysKeepsLegacyFallbackSemantics() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(keyRepository.findByOrganizationAndCapability("org", "text")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(false));
        when(platformModelControlPlane.resolve("text")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel(
                        UUID.randomUUID(), "qwen", "qwen-plus", "http://host", 1, "primary", 4))));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", true).block();

        assertThat(r.isPlatform()).isTrue();
        verifyNoMoreInteractions(policyRepository);
    }

    @Test
    @DisplayName("个人用户（无组织）：无 BYOK + allowFallback=false → DENIED，不扣平台额度")
    void noByokFallbackUnauthorized() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());

        ProviderResolution r = service.resolveProvider(null, "acct", "text", false).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("fallback_not_authorized");
    }

    @Test
    @DisplayName("个人用户：无 BYOK + allowFallback=true + 无平台配置 → DENIED(no_platform_model)")
    void noByokNoPlatformModel() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("text")).thenReturn(Mono.just(Optional.empty()));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("no_platform_model");
    }
}
