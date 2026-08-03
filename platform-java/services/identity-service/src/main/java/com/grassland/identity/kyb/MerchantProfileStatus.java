package com.grassland.identity.kyb;

/**
 * 商家资料审核状态。GL-P3-MERCHANT-001。
 *
 * <p>状态流：{@link #DRAFT} → {@link #PENDING} → {@link #UNDER_REVIEW} → {@link #APPROVED} 或 {@link #REJECTED}。
 */
public enum MerchantProfileStatus {
    DRAFT("draft"),
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String dbValue;

    MerchantProfileStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 是否可编辑（仅 draft 状态可编辑）。 */
    public boolean isEditable() {
        return this == DRAFT;
    }

    /** 是否终态（approved/rejected，不可再变更）。 */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static MerchantProfileStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("merchant profile status is null");
        }
        String normalized = value.trim().toLowerCase();
        for (MerchantProfileStatus s : values()) {
            if (s.dbValue.equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown merchant profile status: " + value);
    }
}
