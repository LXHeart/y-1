package com.grassland.intelligence.contentlibrary;

import java.util.Locale;

/**
 * 内容素材库类型（草场 PRD §4.8 / Slice 14）。三类素材库的归属与授权模型不同（见 V18 migration 注释）：
 *
 * <ul>
 *   <li>{@link #PERSONAL} — 个人素材库（用户上传图/视频/文案/历史作品 + 收藏生成结果）。owner=上传者，无 org。</li>
 *   <li>{@link #MERCHANT} — 商家素材库（门店照/菜单/产品图/品牌 Logo/活动信息）。owner=商家成员，绑 org，
 *       可授权推荐官使用。</li>
 *   <li>{@link #PUBLIC} — 公共与 AI 素材库（平台审核的公共行业素材 + AI 生成元素）。运营上传，强制
 *       source/license_scope/valid_until，全员可读。</li>
 * </ul>
 */
public enum LibraryType {
    PERSONAL("personal"),
    MERCHANT("merchant"),
    PUBLIC("public");

    private final String db;

    LibraryType(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按请求字符串解析；非法值返回 null（调用方据此 400）。大小写不敏感。 */
    public static LibraryType fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LibraryType type : values()) {
            if (type.db.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
