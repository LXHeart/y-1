package com.grassland.intelligence.credits;

/**
 * 积分扣减功能键（与 legacy {@code credit_transactions.feature} 完全一致，6 个）。
 * intelligence 业务模块迁入时按动作选键；冒烟端点不扣积分。
 */
public enum CreditFeature {
    VIDEO_ANALYSIS("video_analysis"),
    IMAGE_ANALYSIS("image_analysis"),
    ARTICLE_GENERATION("article_generation"),
    COMEDY_GENERATION("comedy_generation"),
    VIDEO_PRODUCTION_SCRIPT("video_production_script"),
    VIDEO_PRODUCTION_VIDEO("video_production_video");

    private final String key;

    CreditFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
