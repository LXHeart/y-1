package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 平台图像 provider 解析（2026-08-30 PRD §4.10；任务书 #58 决策 G 收口）：治理台
 * {@code image_generation} 行是唯一平台路径，静态 env 兜底（原 image-generation/qwen 两组 env）
 * 已删——无行即 {@code no_platform_model} 503（fail-closed）。商家/用户 BYOK 层由
 * {@link ByokRoutingService#resolveProvider} 在更外层先行命中。
 *
 * <p>计费口径不变：平台图像 run 按价目字段（unitPriceCents/pricingVersion，{@link ImageGenerationConfig}
 * 计价线）预留结算，价目表覆盖图像模型后再切 {@code PriceTableService}（登记边界）。
 */
@Service
public class PlatformImageResolutionService {

    private final PlatformModelControlPlaneService controlPlane;

    public PlatformImageResolutionService(PlatformModelControlPlaneService controlPlane) {
        this.controlPlane = controlPlane;
    }

    /** 平台图像 provider：治理台控制面行（带 configId/凭据）；无行 → 503 no_platform_model。 */
    public Mono<ProviderResolution> platformResolution() {
        return controlPlane.resolve("image_generation")
                .flatMap(opt -> opt
                        .<Mono<ProviderResolution>>map(row -> Mono.just(toProvider(row)))
                        .orElseGet(() -> Mono.error(new IntelligenceException(
                                503, "no_platform_model", "平台未配置图片生成模型，请到治理台配置"))));
    }

    /** 冻结校验用：当前控制面 image_generation 行（无行 empty）。 */
    public Mono<java.util.Optional<ResolvedPlatformModel>> resolveRow() {
        return controlPlane.resolve("image_generation");
    }

    /**
     * 出图端点：BYOK/带凭据的平台行用执行环解密的明文。其余（无凭据控制面行）在 prepare 阶段
     * 已 503 fail-closed（决策 E/G：无凭据平台行用 env key 的分支已删）。
     */
    public ImageGenerationClient.Endpoint endpointFor(ProviderResolution provider,
            AiExecutionService.ExecutionContext context) {
        if (provider.isByok() || provider.needsKeyDecryption()) {
            return ImageGenerationClient.Endpoint.of(provider, context.decryptedKey());
        }
        throw new IntelligenceException(503, "平台凭据缺失：该能力的凭据未配置密钥");
    }

    private static ProviderResolution toProvider(ResolvedPlatformModel rpm) {
        return ProviderResolution.platform(rpm.configId(), rpm.provider(), rpm.baseUrl(), rpm.model(),
                rpm.version(), rpm.maxConcurrency(), rpm.credentialEncryptedKey(), rpm.credentialVersion());
    }
}
