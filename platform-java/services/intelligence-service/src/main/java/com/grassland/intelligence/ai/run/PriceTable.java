package com.grassland.intelligence.ai.run;

import java.util.Map;
import java.util.Set;

/**
 * AI 价目表（GL-P3-AI-001 Phase 4）。
 * <p>定义每个能力、每个模型的价格（单位：分/计量单位）。
 *
 * <p>价目表版本化，变更发新版本；存量 Run 按其时点价目表结算。
 */
public record PriceTable(
    String version,                   // v1, v2, ...
    Map<String, ModelPrice> models    // modelId -> ModelPrice
) {
    /** 单个模型的价格配置。 */
    public record ModelPrice(
        String capability,            // text/image_generation/video
        String provider,              // qwen/openai-compatible
        int centsPer1kInputTokens,    // 输入 token 单价（分/1k tokens）
        int centsPer1kOutputTokens,   // 输出 token 单价（分/1k tokens）
        int centsPerImage,            // 图片单价（分/张）
        int centsPerSecond            // 视频单价（分/秒）
    ) {
        /** 计算文本生成成本（token → 分）。 */
        public int calculateTextCost(int inputTokens, int outputTokens) {
            int inputCost = (inputTokens * centsPer1kInputTokens + 999) / 1000;
            int outputCost = (outputTokens * centsPer1kOutputTokens + 999) / 1000;
            return inputCost + outputCost;
        }

        /** 计算图片生成成本（张 → 分）。 */
        public int calculateImageCost(int imageCount) {
            return imageCount * centsPerImage;
        }

        /** 计算视频生成成本（秒 → 分）。 */
        public int calculateVideoCost(int seconds) {
            return seconds * centsPerSecond;
        }

        /** 估算成本（用于预算预留）。 */
        public int estimateCost(int estimatedTokens, int estimatedImages, int estimatedSeconds) {
            int tokenCost = (estimatedTokens * centsPer1kInputTokens + 999) / 1000;
            int imageCost = estimatedImages * centsPerImage;
            int videoCost = estimatedSeconds * centsPerSecond;
            return tokenCost + imageCost + videoCost;
        }
    }

    /** 获取模型价格。 */
    public ModelPrice getPrice(String modelId) {
        return models.get(modelId);
    }

    /** 获取所有支持的模型 ID。 */
    public Set<String> supportedModels() {
        return models.keySet();
    }
}
