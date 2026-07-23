package com.grassland.identity.permission;

/**
 * 商家行业分类。草场身份域 Slice 2L（HLD D-05「行业」）。
 *
 * <p>受监管行业（{@link #EDUCATION} / {@link #BEAUTY}）申请升级时须额外提交 {@code industry_license} 材料。
 * DB 存小写 dbValue。{@code null}（未指定）按 {@link #OTHER} 处理。
 */
public enum Industry {
    CATERING("catering"),
    RETAIL("retail"),
    BEAUTY("beauty"),
    EDUCATION("education"),
    E_COMMERCE("e_commerce"),
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
        return this == EDUCATION || this == BEAUTY;
    }

    /** 从 DB 字符串解析，大小写不敏感；null/空 → {@link #OTHER}；非法值抛 {@link IllegalArgumentException}。 */
    public static Industry fromDb(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalized = value.trim().toLowerCase();
        for (Industry industry : values()) {
            if (industry.dbValue.equals(normalized)) {
                return industry;
            }
        }
        throw new IllegalArgumentException("unknown industry: " + value);
    }
}
