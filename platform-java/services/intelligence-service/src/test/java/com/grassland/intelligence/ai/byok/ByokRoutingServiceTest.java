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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * {@link ByokRoutingService} 路由矩阵（HLD §12.3 / ADR-D17 / 任务书 #47 D9+D16）。
 *
 * <p><b>按活动身份分叉</b>：merchant（orgId 非空）→ 组织 &gt; 平台，跳过个人；
 * recommender/消费者（orgId 为 null）→ 个人 &gt; 平台。分叉依据是 edge 的不变量
 * （{@code SessionIdentityResolver:75-80}：只有 merchant 活动身份才带 org/tier），
 * 所以「orgId 非空」在测试里就代表商家视角，「orgId 为 null」代表推荐官视角。
 *
 * <p>回退授权仍分两档：组织未配组织密钥沿用调用方 allowFallback；配置后须组织策略
 * （D16 起无行默认 <b>允许</b>）+ allowFallback 双满足。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ByokRoutingService (个人/组织/平台路由矩阵)")
class ByokRoutingServiceTest {

    @Mock
    AiProviderKeyRepository keyRepository;
    @Mock
    AiOrgByokPolicyRepository policyRepository;
    /** 任务书 #47 S5：个人开关；推荐官分支命中密钥后查它。 */
    @Mock
    AiProviderPreferenceRepository preferenceRepository;
    @Mock
    PlatformModelControlPlaneService platformModelControlPlane;
    ByokRoutingService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 任务书 #58 起构造器带 allow-sandbox 布尔（非 mockable），显式构造（默认关，沙盒用例单独开）
        service = new ByokRoutingService(keyRepository, policyRepository, preferenceRepository,
                platformModelControlPlane, false);
    }

    private static AiProviderKey key(String organizationId, String keyVersion) {
        return new AiProviderKey(UUID.randomUUID(), organizationId, "acct", "text",
                "openai-compatible", "http://host", "byok-model", "ciphertext", keyVersion, "sk-***", true,
                null, null);
    }

    private static AiOrgByokPolicy policy(boolean allowFallback) {
        return new AiOrgByokPolicy("org", allowFallback, 1, "admin-acct", null);
    }

    /**
     * D9 的核心断言，与改造前<b>相反</b>：商家视角下个人密钥不参与，即使它存在。
     * 改造前这里命中个人密钥（`byok:v1`）；现在必须命中组织密钥。
     */
    @Test
    @DisplayName("商家视角：有个人密钥也走组织密钥——个人查询根本不发生（D9）")
    void merchantSkipsPersonalKeyEntirely() {
        when(keyRepository.findByOrganizationAndCapability("org", "text")).thenReturn(Mono.just(key("org", "v2")));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", false).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.byokOrganizationId()).isEqualTo("org");
        assertThat(r.modelVersionKey()).isEqualTo("byok-org:v2");
        assertThat(r.chargesPlatformFee()).isFalse();
        // 个人密钥查询一次都不该发出——这是「跳过」而非「降级排序」
        verify(keyRepository).findByOrganizationAndCapability("org", "text");
        verifyNoMoreInteractions(keyRepository);
    }

    /**
     * D9 + D16 合并效果：商家视角下组织没配该 capability 的密钥 → 走平台，<b>不是</b>个人密钥。
     * D16 前这里会 DENIED（策略无行默认拒绝），那正是 org admin 配完 text 后图片能力
     * 对全组织突然不可用的场景。
     */
    @Test
    @DisplayName("商家视角：组织未配该能力密钥 → 平台（不是个人密钥，也不再 DENIED）")
    void merchantFallsToPlatformNotPersonalKey() {
        when(keyRepository.findByOrganizationAndCapability("org", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(true));
        when(policyRepository.find("org")).thenReturn(Mono.empty());   // 无行 → D16 默认允许
        when(platformModelControlPlane.resolve("image_generation")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel(
                        UUID.randomUUID(), "qwen", "qwen-plus", "http://host", 1, "primary", 4))));

        ProviderResolution r = service.resolveProvider("org", "acct", "image_generation", true).block();

        assertThat(r.isPlatform()).isTrue();
        assertThat(r.chargesPlatformFee()).isTrue();
    }

    /**
     * 推荐官视角（edge 保证其断言不带 orgId）：个人密钥仍然生效，行为与 D9 之前逐字节一致。
     * 该账号同时属于某组织也不影响——组织上下文根本不进入推荐官的断言。
     */
    @Test
    @DisplayName("推荐官视角：个人密钥生效，组织层不参与（D9 后行为不变）")
    void recommenderUsesPersonalKey() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.just(key(null, "v1")));
        when(preferenceRepository.isOwnKeyEnabled("acct", "text")).thenReturn(Mono.just(true));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", false).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.needsKeyDecryption()).isTrue();
        assertThat(r.encryptedKey()).isEqualTo("ciphertext");
        assertThat(r.modelVersionKey()).isEqualTo("byok:v1");
        assertThat(r.byokOrganizationId()).isNull();
        assertThat(r.chargesPlatformFee()).isFalse();
        verify(keyRepository).findByPersonalAndCapability("acct", "text");
        verifyNoMoreInteractions(keyRepository);
    }

    /**
     * D16：策略<b>显式</b> false 的组织仍严格 DENIED——那是 org admin 的明示选择，
     * 不被默认值翻转覆盖。（改造前「无行」也走这条；现在无行默认允许，故本例改为显式 false。）
     */
    @Test
    @DisplayName("组织策略显式 false → 仍 DENIED，不静默扣平台额度（D16 保留严格模式）")
    void orgWithExplicitFalsePolicyDeniesFallback() {
        when(keyRepository.findByOrganizationAndCapability("org", "image_generation")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(true));
        when(policyRepository.find("org")).thenReturn(Mono.just(policy(false)));

        ProviderResolution r = service.resolveProvider("org", "acct", "image_generation", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("fallback_not_authorized");
        verifyNoMoreInteractions(platformModelControlPlane);
    }

    @Test
    @DisplayName("组织配了组织密钥：策略显式允许 + 调用方授权 → 回退平台")
    void orgPolicyAndRequestBothAllowFallbackToPlatform() {
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
        when(keyRepository.findByOrganizationAndCapability("org", "text")).thenReturn(Mono.empty());
        when(keyRepository.existsEnabledForOrganization("org")).thenReturn(Mono.just(false));
        when(platformModelControlPlane.resolve("text")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel(
                        UUID.randomUUID(), "qwen", "qwen-plus", "http://host", 1, "primary", 4))));

        ProviderResolution r = service.resolveProvider("org", "acct", "text", true).block();

        assertThat(r.isPlatform()).isTrue();
        verifyNoMoreInteractions(policyRepository);
    }

    /**
     * 任务书 #47 D12/D14：开关 off 时个人密钥不参与路由，但密钥本身不动——
     * 本例证明「有密钥 + 开关 off」走平台，而非 BYOK。
     */
    @Test
    @DisplayName("推荐官：有个人密钥但开关 off → 走平台（密钥保留不用，D12）")
    void personalKeySkippedWhenPreferenceOff() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.just(key(null, "v1")));
        when(preferenceRepository.isOwnKeyEnabled("acct", "text")).thenReturn(Mono.just(false));
        when(platformModelControlPlane.resolve("text")).thenReturn(
                Mono.just(Optional.of(new ResolvedPlatformModel(
                        UUID.randomUUID(), "qwen", "qwen-plus", "http://host", 1, "primary", 4))));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isPlatform()).isTrue();
        assertThat(r.chargesPlatformFee()).isTrue();   // 计费主体变为平台
    }

    /** D14：无偏好行即 on——从未碰过开关的账号与改造前逐字节一致。 */
    @Test
    @DisplayName("推荐官：有个人密钥且无偏好行 → 仍走个人密钥（D14 无行即 on）")
    void personalKeyUsedWhenPreferenceAbsent() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.just(key(null, "v1")));
        when(preferenceRepository.isOwnKeyEnabled("acct", "text")).thenReturn(Mono.just(true));

        ProviderResolution r = service.resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isByok()).isTrue();
        assertThat(r.modelVersionKey()).isEqualTo("byok:v1");
        assertThat(r.chargesPlatformFee()).isFalse();
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

    // ---------- 任务书 #58 决策 F：控制面无行时的能力分级（内置 Sandbox 回落）----------

    private ByokRoutingService serviceWithSandbox(boolean allowSandbox) {
        return new ByokRoutingService(keyRepository, policyRepository, preferenceRepository,
                platformModelControlPlane, allowSandbox);
    }

    @Test
    @DisplayName("决策 F：voice/retrieval/image_edit 无行且 allow-sandbox=true → 内置 Sandbox 平台解析")
    void sandboxCapabilitiesFallBackToBuiltInSandbox() {
        when(keyRepository.findByPersonalAndCapability("acct", "voice")).thenReturn(Mono.empty());
        when(keyRepository.findByPersonalAndCapability("acct", "retrieval")).thenReturn(Mono.empty());
        when(keyRepository.findByPersonalAndCapability("acct", "image_edit")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("voice")).thenReturn(Mono.just(Optional.empty()));
        when(platformModelControlPlane.resolve("retrieval")).thenReturn(Mono.just(Optional.empty()));
        when(platformModelControlPlane.resolve("image_edit")).thenReturn(Mono.just(Optional.empty()));
        ByokRoutingService routing = serviceWithSandbox(true);

        for (String capability : new String[] {"voice", "retrieval", "image_edit"}) {
            ProviderResolution r = routing.resolveProvider(null, "acct", capability, true).block();
            assertThat(r.isPlatform()).as(capability).isTrue();
            assertThat(r.provider()).as(capability).isEqualTo("sandbox");
            assertThat(r.baseUrl()).as(capability).isEqualTo("https://sandbox.invalid");
        }
        assertThat(routing.resolveProvider(null, "acct", "voice", true).block().model())
                .isEqualTo("sandbox-speech-v1");
        assertThat(routing.resolveProvider(null, "acct", "retrieval", true).block().model())
                .isEqualTo("sandbox-embedding-v1");
        assertThat(routing.resolveProvider(null, "acct", "image_edit", true).block().model())
                .isEqualTo("sandbox-matting-v1");
    }

    @Test
    @DisplayName("决策 F：allow-sandbox=false → 无行一律 DENIED（生产防呆）")
    void sandboxCapabilitiesDeniedWhenSandboxDisallowed() {
        when(keyRepository.findByPersonalAndCapability("acct", "voice")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("voice")).thenReturn(Mono.just(Optional.empty()));

        ProviderResolution r = serviceWithSandbox(false).resolveProvider(null, "acct", "voice", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("no_platform_model");
    }

    @Test
    @DisplayName("决策 F：text 经核实无 Sandbox 客户端，allow-sandbox=true 也不回落")
    void textNeverFallsBackToSandbox() {
        when(keyRepository.findByPersonalAndCapability("acct", "text")).thenReturn(Mono.empty());
        when(platformModelControlPlane.resolve("text")).thenReturn(Mono.just(Optional.empty()));

        ProviderResolution r = serviceWithSandbox(true).resolveProvider(null, "acct", "text", true).block();

        assertThat(r.isDenied()).isTrue();
        assertThat(r.denialReason()).isEqualTo("no_platform_model");
    }
}
