package com.grassland.intelligence.articleimage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片生成平台价目与客户端参数（任务书 #58 决策 G：模型端点/凭据/模型名已收口到控制面
 * {@code platform_model_config} + 凭据；这里只保留计价线字段与 HTTP 客户端超时）。
 *
 * <p>价目版本/单价归后续计价收敛线（价目表覆盖图像模型后迁 {@code PriceTableService}）。
 * 任务模式按任务创建时冻结的价目结算；超时参数喂 {@code ImageGenerationClient} 的
 * {@code ManagedWebClientFactory}。
 */
@ConfigurationProperties(prefix = "ai.image-generation")
public record ImageGenerationConfig(
        String pricingVersion,
        int unitPriceCents,
        Long connectTimeoutMs,
        Long readTimeoutMs) {

    public ImageGenerationConfig {
        if (pricingVersion == null || pricingVersion.isBlank()) {
            pricingVersion = "image-config-v1";
        } else {
            pricingVersion = pricingVersion.trim();
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("图片生成单价（unit-price-cents）不能为负数");
        }
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs == null ? 5_000L : connectTimeoutMs);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs == null ? 300_000L : readTimeoutMs);
    }
}
