package com.grassland.identity.permission;

/**
 * 商家权限申请的审核状态。草场身份域 Slice 2H（HLD D-05 地基）。
 *
 * <p>状态流：{@link #PENDING} → {@link #UNDER_REVIEW} → {@link #APPROVED} 或 {@link #REJECTED}。
 * 为兼容旧客户端，审核接口仍可把 pending 原子领取后直接作出决定。
 * {@link #isTerminal()} 判定终态（已审完，不可再审）。
 */
public enum PermissionRequestStatus {
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String dbValue;

    PermissionRequestStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 是否终态（approved/rejected，不可再审核）。 */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static PermissionRequestStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("permission request status is null");
        }
        String normalized = value.trim().toLowerCase();
        for (PermissionRequestStatus s : values()) {
            if (s.dbValue.equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown permission request status: " + value);
    }
}
