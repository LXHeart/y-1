package com.grassland.identity.permission;

/**
 * 商家行业分类。草场身份域 Slice 2L（HLD D-05「行业」）。
 *
 * <p>受监管行业申请升级时须额外提交 {@code industry_license} 材料；博彩/成人内容为禁止准入行业。
 * DB 存小写 dbValue。{@code null}（未指定）按 {@link #OTHER} 处理。
 */
public enum Industry {
    CATERING("catering"),
    RETAIL("retail"),
    BEAUTY("beauty"),
    EDUCATION("education"),
    E_COMMERCE("e_commerce"),
    HEALTHCARE("healthcare"),
    FINANCE("finance"),
    REAL_ESTATE("real_estate"),
    TRAVEL("travel"),
    CHILDREN("children"),
    GAMBLING("gambling"),
    ADULT("adult"),
    OTHER("other");

    private final String dbValue;

    Industry(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 受监管行业（需额外行业许可证材料）。 */
    public boolean requiresIndustryLicense() {
        return this == EDUCATION || this == BEAUTY || this == HEALTHCARE
                || this == FINANCE || this == REAL_ESTATE || this == CHILDREN;
    }

    public boolean isProhibited() {
        return this == GAMBLING || this == ADULT;
    }

    public boolean isRestricted() {
        return requiresIndustryLicense() || isProhibited();
    }

    /** 从 DB 字符串解析，大小写不敏感；null/空 → {@link #OTHER}；非法值抛 {@link IllegalArgumentException}。 */
    public static Industry fromDb(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalized = value.trim().toLowerCase();
        normalized = switch (normalized) {
            case "餐饮" -> "catering";
            case "零售" -> "retail";
            case "美业", "美容" -> "beauty";
            case "教育" -> "education";
            case "电商", "电子商务" -> "e_commerce";
            case "医疗", "医疗健康" -> "healthcare";
            case "金融", "金融服务" -> "finance";
            case "房地产" -> "real_estate";
            case "旅游" -> "travel";
            case "母婴", "儿童", "母婴儿童" -> "children";
            case "博彩" -> "gambling";
            case "成人内容" -> "adult";
            case "其他" -> "other";
            default -> normalized;
        };
        for (Industry industry : values()) {
            if (industry.dbValue.equals(normalized)) {
                return industry;
            }
        }
        throw new IllegalArgumentException("unknown industry: " + value);
    }
}
