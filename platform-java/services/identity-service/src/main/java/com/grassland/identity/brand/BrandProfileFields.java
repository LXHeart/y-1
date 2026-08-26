package com.grassland.identity.brand;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.permission.Industry;

/**
 * 品牌资料字段归一化与帽（#32 D2，照 {@code store/StoreMarketingFields} 集中定义惯例）。
 *
 * <p>文本字段 blank → null（清空语义）；trim 后超帽 400。经营分类存 {@link Industry} 枚举 dbValue，
 * 非法值 400（不用自由文本）。
 */
public final class BrandProfileFields {

    public static final int BRAND_NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 2000;

    private BrandProfileFields() {
    }

    /** 品牌名称：blank → null；trim 后超帽 400。 */
    public static String brandName(String value) {
        return optional(value, BRAND_NAME_MAX, "品牌名称");
    }

    /** 品牌简介：blank → null；trim 后超帽 400。 */
    public static String description(String value) {
        return optional(value, DESCRIPTION_MAX, "品牌简介");
    }

    /**
     * 经营分类：blank → null；其余解析为 {@link Industry} dbValue（大小写不敏感），非法值 400。
     * 品牌资料仅开放 10 个常规行业——博彩/成人内容为禁止准入行业、不提供「其他」（前端下拉同口径），
     * 三者提交均 400。
     */
    public static String industry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Industry industry;
        try {
            industry = Industry.fromDb(value);
        } catch (IllegalArgumentException e) {
            throw new IdentityException(400, "经营分类无效");
        }
        if (industry.isProhibited() || industry == Industry.OTHER) {
            throw new IdentityException(400, "经营分类不支持该行业");
        }
        return industry.dbValue();
    }

    private static String optional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IdentityException(400, label + "最多 " + maxLength + " 字");
        }
        return normalized;
    }
}
