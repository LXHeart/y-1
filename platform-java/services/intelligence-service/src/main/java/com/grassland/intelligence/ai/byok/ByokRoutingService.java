package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * BYOK 与平台模型分发服务（GL-P3-AI-001 Phase 5 / 控制面闭环）。
 *
 * <p>运行时按能力解析 provider：当前账号的个人 BYOK 优先；无 BYOK 时按 {@code allowFallback} 决定回落平台或拒绝。
 * 组织级 BYOK 在组织角色与管理策略具备权威校验前保持关闭，因此 {@code organizationId} 不参与 Key 查询。
 * <b>HLD §12.3 硬规则</b>：无 BYOK 且 {@code allowFallback=false} 时返回 {@link ResolutionType#DENIED}——
 * 不静默扣平台额度（回退须经调用方显式授权）。
 *
 * <p>解密<b>不在本层</b>：BYOK 解析仍回传密文 {@code encryptedKey}，明文解密在执行层
 * （{@code AiExecutionService} 经 {@code EnvelopeEncryption.decrypt}）按需进行，绝不入日志/响应。
 */
@Service
public class ByokRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(ByokRoutingService.class);

    private final AiProviderKeyRepository keyRepository;
    private final PlatformModelControlPlaneService platformModelControlPlane;

    public ByokRoutingService(
            AiProviderKeyRepository keyRepository,
            PlatformModelControlPlaneService platformModelControlPlane) {
        this.keyRepository = keyRepository;
        this.platformModelControlPlane = platformModelControlPlane;
    }

    /**
     * 解析目标 AI provider 配置。
     *
     * @param organizationId 组织 ID（个人用户为 null）
     * @param accountId 账号 ID
     * @param capability 能力（text/image_generation 等）
     * @param allowFallback 无 BYOK 时是否允许回落平台模型（HLD §12.3 须显式授权）
     * @return provider 解析结果（BYOK / PLATFORM / DENIED）
     */
    public Mono<ProviderResolution> resolveProvider(
            String organizationId,
            String accountId,
            String capability,
            boolean allowFallback) {

        Mono<AiProviderKey> byokLookup = keyRepository.findByPersonalAndCapability(accountId, capability);

        return byokLookup
                .map(key -> ProviderResolution.byok(
                        key.provider(), key.baseUrl(), key.model(), key.encryptedKey(), key.keyVersion()))
                .switchIfEmpty(Mono.defer(() -> {
                    if (!allowFallback) {
                        logger.info("No BYOK for capability={} and fallback not authorized → deny", capability);
                        return Mono.just(ProviderResolution.denied("fallback_not_authorized"));
                    }
                    return platformModelControlPlane.resolve(capability)
                            .map(opt -> opt
                                    .map(ByokRoutingService::toPlatform)
                                    .orElseGet(() -> ProviderResolution.denied("no_platform_model")));
                }));
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
            boolean chargesPlatformFee, // 是否收平台 AI 费（仅平台模型）
            int platformModelVersion,   // 平台配置版本（TaskContext 冻结用）；非平台为 0
            UUID platformConfigId,
            Integer maxConcurrency,
            String denialReason         // DENIED 时的原因；其余为 null
    ) {
        public static ProviderResolution byok(
                String provider, String baseUrl, String model, String encryptedKey, String keyVersion) {
            return new ProviderResolution(ResolutionType.BYOK, provider, baseUrl, model, encryptedKey,
                    keyVersion, false, 0, null, null, null);
        }

        public static ProviderResolution platform(
                UUID configId, String provider, String baseUrl, String model,
                int version, Integer maxConcurrency) {
            return new ProviderResolution(ResolutionType.PLATFORM, provider, baseUrl, model, null,
                    null, true, version, configId, maxConcurrency, null);
        }

        public static ProviderResolution denied(String reason) {
            return new ProviderResolution(
                    ResolutionType.DENIED, null, null, null, null, null, false, 0, null, null, reason);
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
                return "byok:" + keyVersion;
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
