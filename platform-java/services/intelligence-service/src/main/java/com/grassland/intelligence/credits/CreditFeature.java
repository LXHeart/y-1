package com.grassland.intelligence.credits;

/**
 * 积分扣减功能键（写入 legacy {@code credit_transactions.feature}）。前 6 个与 legacy 完全一致；
 * {@link #INTELLIGENCE_SMOKE} 是 Java 侧新增键，用于冒烟端点。
 *
 * <p>legacy 侧 {@code feature} 是无 CHECK 约束的 {@code text} 列，单次扣减恒为 1 积分
 * （{@code balance - 1}），因此新增键不需要 migration。
 */
public enum CreditFeature {
    VIDEO_ANALYSIS("video_analysis"),
    IMAGE_ANALYSIS("image_analysis"),
    ARTICLE_GENERATION("article_generation"),
    COMEDY_GENERATION("comedy_generation"),
    VIDEO_PRODUCTION_SCRIPT("video_production_script"),
    VIDEO_PRODUCTION_VIDEO("video_production_video"),
    /** 冒烟端点：真实消耗 Qwen 上游，与其它 AI 调用同等扣分（GL-P0-SEC-002，原先免费）。 */
    INTELLIGENCE_SMOKE("intelligence_smoke");

    private final String key;

    CreditFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
