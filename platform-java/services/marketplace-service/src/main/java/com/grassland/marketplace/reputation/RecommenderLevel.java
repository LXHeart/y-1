package com.grassland.marketplace.reputation;

/** 推荐官等级（PRD 五）。{@code code} 是对外稳定标识，{@code title} 是展示名。 */
public enum RecommenderLevel {
    LV1("Lv1", "新手草友"),
    LV2("Lv2", "活跃草友"),
    LV3("Lv3", "优质草友"),
    LV4("Lv4", "金牌草友"),
    LV5("Lv5", "草场达人");

    private final String code;
    private final String title;

    RecommenderLevel(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public int number() {
        return ordinal() + 1;
    }
}
