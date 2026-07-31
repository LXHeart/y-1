package com.grassland.identity.notification;

/**
 * 站内通知分类。草场 Slice 12。
 *
 * <p>分类按**用户视角的事情种类**划分，不按发源服务划分——用户不关心「这条来自 trust 还是 marketplace」，
 * 只关心「是邀请、是履约、还是钱」。故 {@link #ENGAGEMENT} 同时容纳 marketplace 的报名/交付/结算。
 *
 * <p>DB 存小写 {@link #dbValue()}，与 {@code MembershipRole} / {@code organization.status} 的 lower-case 约定一致。
 */
public enum NotificationCategory {
    /** 组织邀请与成员关系。 */
    INVITATION("invitation"),
    /** 商家权限审核（D-05）。 */
    PERMISSION("permission"),
    /** 履约链路：报名、接受/拒绝、交付、核验、结算。 */
    ENGAGEMENT("engagement"),
    /** 争议与审判。 */
    DISPUTE("dispute"),
    /** 钱包与资金。 */
    WALLET("wallet"),
    /** 平台通告等非领域事件派生的通知。 */
    SYSTEM("system");

    private final String dbValue;

    NotificationCategory(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static NotificationCategory fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("notification category is null");
        }
        String normalized = value.trim().toLowerCase();
        for (NotificationCategory category : values()) {
            if (category.dbValue.equals(normalized)) {
                return category;
            }
        }
        throw new IllegalArgumentException("unknown notification category: " + value);
    }
}
