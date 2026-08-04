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

    /**
     * 是否可编辑。pending 与 **rejected** 都可编辑——被拒账户须能改完重新提交
     * （与 {@link MerchantProfileStatus#isEditable()} 同口径）。under_review 审核中不可改。
     */
    public boolean isEditable() {
        return this == PENDING || this == REJECTED;
    }

    /** 是否可删除（pending/rejected 可删；审核中与已批准的收款账户不可删）。 */
    public boolean isDeletable() {
        return this == PENDING || this == REJECTED;
    }

    /** 是否可提交审核（与可编辑同集合）。 */
    public boolean canSubmit() {
        return isEditable();
    }

    /** 是否终态。**只有 approved 是终态**——rejected 可改后重新提交。 */
    public boolean isTerminal() {
        return this == APPROVED;
    }

    /** 是否审核中（under_review）——不可编辑、不可重复提交。 */
    public boolean isUnderReview() {
        return this == UNDER_REVIEW;
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
