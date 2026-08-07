package com.grassland.intelligence.contentlibrary;

import java.util.Locale;

/**
 * 素材分类（草场 PRD §4.8）。PRD §4.8 商家素材库「按门店、产品、活动、场景和有效期分类」；个人素材库
 * 含文案/历史作品；扩展 {@link #COPY}（文案）与 {@link #OTHER} 兜底。
 */
public enum AssetCategory {
    STORE("store"),       // 门店照/门店素材
    PRODUCT("product"),   // 产品图/菜单
    CAMPAIGN("campaign"), // 活动信息
    SCENE("scene"),       // 场景
    BRAND("brand"),       // 品牌 Logo/品牌素材
    COPY("copy"),         // 文案/历史作品（个人库常用）
    OTHER("other");

    private final String db;

    AssetCategory(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按请求字符串解析；非法值返回 null（调用方据此 400）。大小写不敏感。 */
    public static AssetCategory fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AssetCategory category : values()) {
            if (category.db.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
