package com.grassland.identity.organization;

/**
 * 三级商家准入权限。草场身份域 Slice 2F（HLD 1.3 事实 7）。
 *
 * <p>声明顺序即准入高低：{@link #DRAFT}（草稿，最低）&lt; {@link #BASIC_PUBLISH}（基础发布）
 * &lt; {@link #FINANCE_TRANSACTION}（资金交易，最高）。grant 仅允许单调升级（{@link #isAtLeast}）。
 * DB 存小写 dbValue，与现有 lower-case 约定一致。
 */
public enum PermissionTier {
    DRAFT("draft"),
    BASIC_PUBLISH("basic_publish"),
    FINANCE_TRANSACTION("finance_transaction");

    private final String dbValue;

    PermissionTier(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 当前准入等级是否不低于给定等级（ordinal 越大等级越高）。 */
    public boolean isAtLeast(PermissionTier required) {
        return this.ordinal() >= required.ordinal();
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static PermissionTier fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("permission tier is null");
        }
        String normalized = value.trim().toLowerCase();
        for (PermissionTier tier : values()) {
            if (tier.dbValue.equals(normalized)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("unknown permission tier: " + value);
    }
}
