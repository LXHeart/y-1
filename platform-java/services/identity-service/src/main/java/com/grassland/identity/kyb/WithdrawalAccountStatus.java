package com.grassland.identity.kyb;

/**
 * 收款账户审核状态。GL-P3-MERCHANT-001。
 *
 * <p>状态流：{@link #PENDING} → {@link #UNDER_REVIEW} → {@link #APPROVED} 或 {@link #REJECTED}。
 */
public enum WithdrawalAccountStatus {
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String dbValue;

    WithdrawalAccountStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 是否可编辑（仅 pending 状态可编辑）。 */
    public boolean isEditable() {
        return this == PENDING;
    }

    /** 是否可删除（仅 pending 状态可删除）。 */
    public boolean isDeletable() {
        return this == PENDING;
    }

    /** 是否终态（approved/rejected，不可再变更）。 */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static WithdrawalAccountStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("withdrawal account status is null");
        }
        String normalized = value.trim().toLowerCase();
        for (WithdrawalAccountStatus s : values()) {
            if (s.dbValue.equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown withdrawal account status: " + value);
    }
}
