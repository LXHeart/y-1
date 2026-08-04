package com.grassland.identity.kyb;

/**
 * {@code kyb_verification_request.status} 状态。GL-P3-MERCHANT-001。
 *
 * <p>此前 controller 内散落 {@code !status.equals("approved") && !status.equals("rejected")} 的字符串比较，
 * 集中到这里以免各处漂移。
 */
public enum KybRequestStatus {
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String dbValue;

    KybRequestStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 是否终态（已审完，不可再审）。 */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }

    /** 是否待处理（在 admin 队列中）。 */
    public static boolean isOpen(String dbValue) {
        return !fromDb(dbValue).isTerminal();
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static KybRequestStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("kyb request status is null");
        }
        String normalized = value.trim().toLowerCase();
        for (KybRequestStatus s : values()) {
            if (s.dbValue.equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown kyb request status: " + value);
    }
}
