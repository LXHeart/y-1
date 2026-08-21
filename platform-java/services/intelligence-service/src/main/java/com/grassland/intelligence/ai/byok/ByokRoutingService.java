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
 * <p>运行时按能力解析 provider，层级为：<b>个人 BYOK &gt; 组织 BYOK &gt; 平台模型</b>。
 * 个人优先保证既有语义零变更；组织密钥是成员无个人密钥时的兜底（成员「可用不可见」，D-11）。
 *
 * <p>平台回退（两级密钥都未命中）的授权口径：
 * <ul>
 *   <li>组织<b>未</b>配置任何有效组织密钥：沿用调用方显式 {@code allowFallback}（与组织级开启前一致）；</li>
 *   <li>组织配置了有效组织密钥（即「组织选择了 BYOK」）：须组织回退策略
 *       （{@code ai_org_byok_policy}，默认不允许）<b>且</b> {@code allowFallback} 双满足，否则 DENIED
 *       ——不静默扣平台额度（D-11 / HLD §12.3 硬规则）。</li>
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
    private final PlatformModelControlPlaneService platformModelControlPlane;

    public ByokRoutingService(
            AiProviderKeyRepository keyRepository,
            AiOrgByokPolicyRepository policyRepository,
            PlatformModelControlPlaneService platformModelControlPlane) {
        this.keyRepository = keyRepository;
        this.policyRepository = policyRepository;
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

        return keyRepository.findByPersonalAndCapability(accountId, capability)
                .map(key -> ProviderResolution.byok(
                        key.provider(), key.baseUrl(), key.model(), key.encryptedKey(), key.keyVersion(), null))
                .switchIfEmpty(Mono.defer(() ->
                        organizationId == null
                                ? fallbackStage(capability, allowFallback)
                                : resolveOrgTier(organizationId, capability, allowFallback)));
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
                            return policyRepository.find(organizationId)
                                    .map(AiOrgByokPolicy::allowPlatformFallback)
                                    .defaultIfEmpty(false)
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
        return ProviderResolution.platform(rpm.configId(), rpm.provider(), rpm.baseUrl(), rpm.model(), rpm.version(),
                rpm.maxConcurrency());
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
            String denialReason         // DENIED 时的原因；其余为 null
    ) {
        public static ProviderResolution byok(
                String provider, String baseUrl, String model, String encryptedKey, String keyVersion,
                String byokOrganizationId) {
            return new ProviderResolution(ResolutionType.BYOK, provider, baseUrl, model, encryptedKey,
                    keyVersion, byokOrganizationId, false, 0, null, null, null);
        }

        /** 个人 BYOK（组织维度为 null）。 */
        public static ProviderResolution byok(
                String provider, String baseUrl, String model, String encryptedKey, String keyVersion) {
            return byok(provider, baseUrl, model, encryptedKey, keyVersion, null);
        }

        public static ProviderResolution platform(
                UUID configId, String provider, String baseUrl, String model,
                int version, Integer maxConcurrency) {
            return new ProviderResolution(ResolutionType.PLATFORM, provider, baseUrl, model, null,
                    null, null, true, version, configId, maxConcurrency, null);
        }

        public static ProviderResolution denied(String reason) {
            return new ProviderResolution(
                    ResolutionType.DENIED, null, null, null, null, null, null, false, 0, null, null, reason);
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

        /** BYOK 是否需要解密（有密文）。 */
        public boolean needsKeyDecryption() {
            return isByok() && encryptedKey != null;
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
