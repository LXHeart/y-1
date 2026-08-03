package com.grassland.intelligence.ai.run;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * AI 价目表服务（GL-P3-AI-001 Phase 4）。
 * <p>维护当前价目表，提供成本估算和结算计算。
 *
 * <p>首期使用硬编码默认价目表；后续支持从数据库/配置加载。
 */
@Service
public class PriceTableService {

    private final Map<String, PriceTable> versionedTables = new HashMap<>();

    public PriceTableService() {
        // 首期默认价目表（v1）
        PriceTable v1 = new PriceTable("v1", buildDefaultPrices());
        versionedTables.put("v1", v1);
    }

    /** 获取当前最新价目表。 */
    public PriceTable getCurrent() {
        return versionedTables.get("v1");
    }

    /** 获取指定版本价目表。 */
    public PriceTable getVersion(String version) {
        return versionedTables.get(version);
    }

    /** 计算成本（按实际用量）。 */
    public int calculateCost(
        String modelId,
        int inputTokens,
        int outputTokens,
        int imagesGenerated,
        int videoSeconds) {

        PriceTable table = getCurrent();
        PriceTable.ModelPrice price = table.getPrice(modelId);
        if (price == null) {
            throw new IllegalArgumentException("Unknown model: " + modelId);
        }

        int total = 0;
        total += price.calculateTextCost(inputTokens, outputTokens);
        total += price.calculateImageCost(imagesGenerated);
        total += price.calculateVideoCost(videoSeconds);
        return total;
    }

    /** 估算成本（用于预算预留）。 */
    public int estimateCost(
        String modelId,
        int estimatedTokens,
        int estimatedImages,
        int estimatedSeconds) {

        PriceTable table = getCurrent();
        PriceTable.ModelPrice price = table.getPrice(modelId);
        if (price == null) {
            throw new IllegalArgumentException("Unknown model: " + modelId);
        }

        return price.estimateCost(estimatedTokens, estimatedImages, estimatedSeconds);
    }

    /** 构建默认价目表（首期硬编码）。 */
    private Map<String, PriceTable.ModelPrice> buildDefaultPrices() {
        Map<String, PriceTable.ModelPrice> prices = new HashMap<>();

        // Qwen 通义千问系列
        prices.put("qwen-turbo", new PriceTable.ModelPrice(
            "text", "qwen",
            1,   // 0.01 元/1k tokens
            2,   // 0.02 元/1k tokens
            80,  // 0.8 元/张（图片）
            10   // 0.1 元/秒（视频）
        ));
        prices.put("qwen-plus", new PriceTable.ModelPrice(
            "text", "qwen",
            3,   // 0.03 元/1k tokens
            6,   // 0.06 元/1k tokens
            200, // 2 元/张
            30   // 0.3 元/秒
        ));
        prices.put("qwen-max", new PriceTable.ModelPrice(
            "text", "qwen",
            10,  // 0.1 元/1k tokens
            20,  // 0.2 元/1k tokens
            500, // 5 元/张
            100  // 1 元/秒
        ));

        // 图片生成专用模型
        prices.put("wanx-v1", new PriceTable.ModelPrice(
            "image_generation", "qwen",
            1, 2, 80, 0   // 图片生成模型，视频单价为 0
        ));

        // OpenAI 兼容系列（参考价格，实际由用户 BYOK）
        prices.put("gpt-3.5-turbo", new PriceTable.ModelPrice(
            "text", "openai-compatible",
            2,   // 0.02 元/1k tokens
            4,   // 0.04 元/1k tokens
            0,   // 不支持图片
            0    // 不支持视频
        ));
        prices.put("gpt-4", new PriceTable.ModelPrice(
            "text", "openai-compatible",
            30,  // 0.3 元/1k tokens
            60,  // 0.6 元/1k tokens
            0,
            0
        ));

        return prices;
    }
}
