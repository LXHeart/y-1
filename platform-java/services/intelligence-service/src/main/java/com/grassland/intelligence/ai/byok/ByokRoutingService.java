package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.ai.run.PriceTableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * BYOK 与平台模型分发服务（GL-P3-AI-001 Phase 5）。
 * <p>运行时根据组织策略选择平台模型或 BYOK 模型。
 */
@Service
public class ByokRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(ByokRoutingService.class);

    private final AiProviderKeyRepository keyRepository;
    private final PriceTableService priceTableService;

    public ByokRoutingService(
            AiProviderKeyRepository keyRepository,
            PriceTableService priceTableService) {
        this.keyRepository = keyRepository;
        this.priceTableService = priceTableService;
    }

    /**
     * 解析目标 AI provider 配置。
     *
     * @param organizationId 组织 ID（个人用户为 null）
     * @param accountId 账号 ID
     * @param capability 能力（text/image_generation 等）
     * @param allowFallback 是否允许回退平台模型（BYOK 失败时）
     * @return Provider 配置解析结果
     */
    public Mono<ProviderResolution> resolveProvider(
            String organizationId,
            String accountId,
            String capability,
            boolean allowFallback) {

        // 优先查找组织级 BYOK 配置
        if (organizationId != null) {
            return keyRepository.findByOrganizationAndCapability(organizationId, capability)
                    .map(key -> ProviderResolution.byok(
                        key.provider(),
                        key.baseUrl(),
                        key.model(),
                        key.encryptedKey(),
                        false  // BYOK 不收平台 AI 费
                    ))
                    .switchIfEmpty(
                        // 组织无 BYOK，回退平台模型
                        Mono.fromCallable(() -> ProviderResolution.platform(allowFallback))
                    );
        }

        // 个人用户查找个人 BYOK
        return keyRepository.findByPersonalAndCapability(accountId, capability)
                .map(key -> ProviderResolution.byok(
                    key.provider(),
                    key.baseUrl(),
                    key.model(),
                    key.encryptedKey(),
                    false
                ))
                .switchIfEmpty(
                    // 个人无 BYOK，回退平台模型
                    Mono.fromCallable(() -> ProviderResolution.platform(allowFallback))
                );
    }

    /**
     * Provider 配置解析结果。
     */
    public record ProviderResolution(
        ResolutionType type,       // byok / platform
        String provider,            // qwen/openai-compatible
        String baseUrl,             // API endpoint
        String model,               // 模型名称
        String encryptedKey,        // BYOK 密文（platform 时为 null）
        boolean chargesPlatformFee  // 是否收平台 AI 费
    ) {
        /** BYOK 解析结果。 */
        public static ProviderResolution byok(
                String provider,
                String baseUrl,
                String model,
                String encryptedKey,
                boolean chargesPlatformFee) {
            return new ProviderResolution(
                ResolutionType.BYOK,
                provider,
                baseUrl,
                model,
                encryptedKey,
                chargesPlatformFee
            );
        }

        /** 平台模型解析结果。 */
        public static ProviderResolution platform(boolean allowFallback) {
            return new ProviderResolution(
                ResolutionType.PLATFORM,
                "platform",  // 使用平台默认 provider
                null,        // baseUrl 从环境变量读取
                null,        // model 从环境变量读取
                null,        // 无 BYOK 密钥
                true         // 平台模型收 AI 费
            );
        }

        /** 是否是 BYOK。 */
        public boolean isByok() {
            return type == ResolutionType.BYOK;
        }

        /** 是否是平台模型。 */
        public boolean isPlatform() {
            return type == ResolutionType.PLATFORM;
        }

        /** 是否需要解密密钥（BYOK）。 */
        public boolean needsKeyDecryption() {
            return isByok() && encryptedKey != null;
        }
    }

    /** 解析类型。 */
    public enum ResolutionType {
        /** 用户自带 Key。 */
        BYOK,
        /** 平台默认模型。 */
        PLATFORM
    }
}
