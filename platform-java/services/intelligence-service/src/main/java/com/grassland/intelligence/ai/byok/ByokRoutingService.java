package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * BYOK 与平台模型分发服务（GL-P3-AI-001 Phase 5 / 控制面闭环；组织级 ADR-D17）。
 *
 * <p><b>任务书 #47 D9 起按活动身份分叉</b>（不再是单一优先级链）：
 * <ul>
 *   <li><b>merchant 活动身份</b>（{@code organizationId} 非空）→ <b>组织 BYOK &gt; 平台</b>，
 *       跳过个人密钥。商家侧由组织统一配置模型，个人密钥数据保留但不参与路由。</li>
 *   <li><b>recommender / 消费者</b>（{@code organizationId} 为 null）→ <b>个人 BYOK &gt; 平台</b>。
 *       这一支在 D9 之前就是现状（orgId 恒 null 时原本也进不了组织层），故推荐官行为零变更。</li>
 * </ul>
 * 分叉依据是 edge 的不变量：{@code SessionIdentityResolver:75-80} 保证只有 merchant 活动身份才带
 * org/tier，其注释明说破坏它就破坏 HLD 7.4「活动身份 ↔ 组织上下文」。故运行时每个 session 的
 * 链路是<b>单一确定</b>的，不存在「同时看两层」的歧义。
 *
 * <p>平台回退的授权口径：
 * <ul>
 *   <li>组织<b>未</b>配置任何有效组织密钥：沿用调用方显式 {@code allowFallback}（与组织级开启前一致）；</li>
 *   <li>组织配置了有效组织密钥（即「组织选择了 BYOK」）：须组织回退策略
 *       （{@code ai_org_byok_policy}，<b>D16 起无行默认允许</b>）<b>且</b> {@code allowFallback} 双满足。
 *       已显式设 {@code false} 的组织仍严格 DENIED——不静默扣平台额度（D-11 / HLD §12.3 硬规则）。</li>
 * </ul>
 *
 * <p>解密<b>不在本层</b>：BYOK 解析仍回传密文 {@code encryptedKey}，明文解密在执行层
 * （{@code AiExecutionService} 经 {@code EnvelopeEncryption.decrypt}）按需进行，绝不入日志/响应。
 */
@Service
public class ByokRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(ByokRoutingService.class);

    private final AiProviderKeyRepository keyRepository;
    private final AiOrgByokPolicyRepository policyRepository;
    private final AiProviderPreferenceRepository preferenceRepository;
    private final PlatformModelControlPlaneService platformModelControlPlane;

    public ByokRoutingService(
            AiProviderKeyRepository keyRepository,
            AiOrgByokPolicyRepository policyRepository,
            AiProviderPreferenceRepository preferenceRepository,
            PlatformModelControlPlaneService platformModelControlPlane) {
        this.keyRepository = keyRepository;
        this.policyRepository = policyRepository;
        this.preferenceRepository = preferenceRepository;
        this.platformModelControlPlane = platformModelControlPlane;
    }

    /**
     * 解析目标 AI provider 配置。
     *
     * @param organizationId 组织 ID（个人用户为 null）
     * @param accountId 账号 ID
     * @param capability 能力（text/image_generation 等）
     * @param allowFallback 两级 BYOK 都未命中时是否允许回落平台模型（HLD §12.3 须显式授权；
     *                      组织配了组织密钥时还须组织策略同时允许）
     * @return provider 解析结果（BYOK / PLATFORM / DENIED）
     */
    public Mono<ProviderResolution> resolveProvider(
            String organizationId,
            String accountId,
            String capability,
            boolean allowFallback) {

        // 任务书 #47 D9：按活动身份分叉。edge 保证「organizationId 非空 ⟺ merchant 活动身份」
        // （SessionIdentityResolver:75-80 的不变量，注释明说破坏它就破坏 HLD 7.4），故非空即商家视角：
        // 商家侧由组织统一配置模型，个人密钥不参与——**跳过个人查询**，不是降级排序。
        if (organizationId != null) {
            return resolveOrgTier(organizationId, capability, allowFallback);
        }
        // 推荐官（及消费者）视角：个人 > 平台。这一条在 D9 之前就是现状——orgId 恒 null 时
        // 原本也走不到组织层，故推荐官行为逐字节不变。
        //
        // 任务书 #47 D11–D14：命中个人密钥后还要看该能力的开关。**先查密钥再查偏好**是刻意的——
        // 绝大多数账号没有个人密钥，反序会让每个请求都多一次无用往返。无偏好行即 on（D14），
        // 所以从未碰过开关的账号连这次查询的结果都与旧行为一致。
        return keyRepository.findByPersonalAndCapability(accountId, capability)
                .flatMap(key -> preferenceRepository.isOwnKeyEnabled(accountId, capability)
                        .flatMap(useOwnKey -> useOwnKey
                                ? Mono.just(ProviderResolution.byok(key.provider(), key.baseUrl(), key.model(),
                                        key.encryptedKey(), key.keyVersion(), null))
                                // 开关 off：密钥密文照旧留在库里（D12 可逆），本次不参与路由
                                : fallbackStage(capability, allowFallback)))
                .switchIfEmpty(Mono.defer(() -> fallbackStage(capability, allowFallback)));
    }

    /** 组织层：组织密钥命中 → BYOK；未命中但组织配有组织密钥 → 按组织策略决定回退。 */
    private Mono<ProviderResolution> resolveOrgTier(String organizationId, String capability, boolean allowFallback) {
        return keyRepository.findByOrganizationAndCapability(organizationId, capability)
                .map(key -> ProviderResolution.byok(
                        key.provider(), key.baseUrl(), key.model(), key.encryptedKey(), key.keyVersion(),
                        organizationId))
                .switchIfEmpty(Mono.defer(() -> keyRepository.existsEnabledForOrganization(organizationId)
                        .flatMap(hasOrgKeys -> {
                            if (!hasOrgKeys) {
                                // 组织未选择 BYOK：与组织级开启前完全一致
                                return fallbackStage(capability, allowFallback);
                            }
                            // 任务书 #47 D16：无行默认翻为 **允许**。D15「组织配了该 capability 的 key
                            // 就用、没配就走平台」与原默认 false（DENIED）在「配了 text、没配 image」时
                            // 直接冲突——保留原默认会让 org admin 配完 text 后，图片能力对全组织突然
                            // 不可用，且他完全不会预期。已显式设 false 的组织仍严格拒绝（那是明示选择）。
                            return policyRepository.find(organizationId)
                                    .map(AiOrgByokPolicy::allowPlatformFallback)
                                    .defaultIfEmpty(true)
                                    .flatMap(orgAllows -> {
                                        if (!orgAllows) {
                                            logger.info("Org={} has BYOK keys but fallback policy not allowed"
                                                    + " for capability={} → deny", organizationId, capability);
                                            return Mono.just(ProviderResolution.denied("fallback_not_authorized"));
                                        }
                                        return fallbackStage(capability, allowFallback);
                                    });
                        })));
    }

    /** 平台回退段：allowFallback 未授权即 DENIED；授权则经控制面解析主/备平台模型。 */
    private Mono<ProviderResolution> fallbackStage(String capability, boolean allowFallback) {
        if (!allowFallback) {
            logger.info("No BYOK for capability={} and fallback not authorized → deny", capability);
            return Mono.just(ProviderResolution.denied("fallback_not_authorized"));
        }
        return platformModelControlPlane.resolve(capability)
                .map(opt -> opt
                        .map(ByokRoutingService::toPlatform)
                        .orElseGet(() -> ProviderResolution.denied("no_platform_model")));
    }

    private static ProviderResolution toPlatform(ResolvedPlatformModel rpm) {
        // 任务书 #47 S2：平台凭据密文随解析结果下传，执行层按需解密；为 null 则回落 env bootstrap（D1/D8）
        return ProviderResolution.platform(rpm.configId(), rpm.provider(), rpm.baseUrl(), rpm.model(), rpm.version(),
                rpm.maxConcurrency(), rpm.credentialEncryptedKey(), rpm.credentialVersion());
    }

    /**
     * Provider 配置解析结果。
     */
    public record ProviderResolution(
            ResolutionType type,
            String provider,            // qwen/openai-compatible（DENIED 时为 null）
            String baseUrl,
            String model,
            String encryptedKey,        // BYOK 密文（platform/denied 时为 null）
            String keyVersion,          // BYOK 路由版本（platform/denied 时为 null）
            String byokOrganizationId,  // 组织密钥命中时的组织 ID（个人 BYOK/platform/denied 为 null）
            boolean chargesPlatformFee, // 是否收平台 AI 费（仅平台模型）
            int platformModelVersion,   // 平台配置版本（TaskContext 冻结用）；非平台为 0
            UUID platformConfigId,
            Integer maxConcurrency,
            String denialReason,        // DENIED 时的原因；其余为 null
            Long credentialVersion      // 平台凭据版本（任务书 #47 D7）；BYOK/无凭据为 null
    ) {
        public static ProviderResolution byok(
                String provider, String baseUrl, String model, String encryptedKey, String keyVersion,
                String byokOrganizationId) {
            return new ProviderResolution(ResolutionType.BYOK, provider, baseUrl, model, encryptedKey,
                    keyVersion, byokOrganizationId, false, 0, null, null, null, null);
        }

        /** 个人 BYOK（组织维度为 null）。 */
        public static ProviderResolution byok(
                String provider, String baseUrl, String model, String encryptedKey, String keyVersion) {
            return byok(provider, baseUrl, model, encryptedKey, keyVersion, null);
        }

        /** 无凭据的平台解析（env bootstrap 兜底路径，以及不经控制面的固定 provider 分支）。 */
        public static ProviderResolution platform(
                UUID configId, String provider, String baseUrl, String model,
                int version, Integer maxConcurrency) {
            return platform(configId, provider, baseUrl, model, version, maxConcurrency, null, null);
        }

        /**
         * 带平台凭据的解析（任务书 #47 S2）。
         *
         * <p>{@code credentialEncryptedKey} 复用 {@code encryptedKey} 字段承载——它此前只服务 BYOK，
         * 语义扩为「本次解析要用的密文，无论来源」。为 null 表示凭据无密钥，执行层回落 env（D1/D8）。
         */
        public static ProviderResolution platform(
                UUID configId, String provider, String baseUrl, String model,
                int version, Integer maxConcurrency, String credentialEncryptedKey, Long credentialVersion) {
            return new ProviderResolution(ResolutionType.PLATFORM, provider, baseUrl, model,
                    credentialEncryptedKey, null, null, true, version, configId, maxConcurrency, null,
                    credentialVersion);
        }

        public static ProviderResolution denied(String reason) {
            return new ProviderResolution(
                    ResolutionType.DENIED, null, null, null, null, null, null, false, 0, null, null, reason, null);
        }

        public boolean isByok() {
            return type == ResolutionType.BYOK;
        }

        public boolean isPlatform() {
            return type == ResolutionType.PLATFORM;
        }

        public boolean isDenied() {
            return type == ResolutionType.DENIED;
        }

        /**
         * 是否需要解密（有密文即需要）。
         *
         * <p>任务书 #47 S2 起<b>不再限定 BYOK</b>：平台凭据也带密文。平台凭据无密钥时仍为 false，
         * 由执行层回落 env bootstrap（D1/D8）。
         */
        public boolean needsKeyDecryption() {
            return encryptedKey != null && !encryptedKey.isBlank();
        }

        /**
         * 平台解析<b>且</b>凭据自带密钥（任务书 #47 S2）。
         *
         * <p>给那些本就有 provider 专属凭据配置的执行点用（Embedding / Speech）：它们的既有优先级是
         * 「专属配置 &gt; env qwen」，而 {@code ExecutionContext.decryptedKey()} 在平台分支已含 env 兜底，
         * 直接替换会让专属配置被 env 悄悄顶掉。用本方法可精确表达「凭据真配了密钥才优先」。
         */
        public boolean hasPlatformCredentialKey() {
            return isPlatform() && needsKeyDecryption();
        }

        public String modelVersionKey() {
            if (isPlatform()) {
                return "platform:" + platformModelVersion;
            }
            if (isByok()) {
                return (byokOrganizationId == null ? "byok:" : "byok-org:") + keyVersion;
            }
            throw new IllegalStateException("拒绝结果没有模型版本");
        }
    }

    /** 解析类型。 */
    public enum ResolutionType {
        /** 用户自带 Key。 */
        BYOK,
        /** 平台默认模型（经控制面解析的主/备）。 */
        PLATFORM,
        /** 拒绝执行（无 BYOK 且回退未授权，或无平台模型可用）。 */
        DENIED
    }
}
