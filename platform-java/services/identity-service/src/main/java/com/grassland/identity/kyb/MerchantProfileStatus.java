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

    /**
     * 是否可编辑。draft 与 **rejected** 都可编辑——被拒商家必须能改完重新提交，
     * 否则一次拒绝就把主体永久锁死（backlog 明确要求「复审」）。
     * pending/under_review 不可编辑（审核中改材料会让审核人看到与提交时不同的内容），
     * approved 不可直接编辑（须走新一轮审核）。
     */
    public boolean isEditable() {
        return this == DRAFT || this == REJECTED;
    }

    /** 是否可提交审核（与可编辑同集合：能改就能提交）。 */
    public boolean canSubmit() {
        return isEditable();
    }

    /**
     * 是否终态。**只有 approved 是终态**——rejected 可改后重新提交，
     * 故不算终态（见 {@link #isEditable()}）。
     */
    public boolean isTerminal() {
        return this == APPROVED;
    }

    /** 是否审核中（pending/under_review）——此时既不可编辑也不可重复提交。 */
    public boolean isUnderReview() {
        return this == PENDING || this == UNDER_REVIEW;
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
