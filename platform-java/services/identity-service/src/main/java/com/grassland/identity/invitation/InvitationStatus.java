package com.grassland.identity.invitation;

/**
 * 组织邀请状态。{@link #PENDING} 为唯一非终态（partial unique 索引也只约束它）。
 *
 * <p>过期**不是**状态：到期靠 {@code expires_at} 与当前时刻比较判定，行仍留在 pending
 * （避免为翻状态引入定时任务；读侧过滤、接受侧守卫各判一次即可）。
 */
public enum InvitationStatus {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REVOKED("revoked"),
    DECLINED("declined");

    private final String dbValue;

    InvitationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static InvitationStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("invitation status is null");
        }
        String normalized = value.trim().toLowerCase();
        for (InvitationStatus status : values()) {
            if (status.dbValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown invitation status: " + value);
    }
}
