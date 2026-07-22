package com.grassland.identity.membership;

/**
 * 组织成员角色。草场身份域 Slice 2F。
 *
 * <p>声明顺序即权限高低：{@link #OWNER}（最高）&gt; {@link #ADMIN} &gt; {@link #MEMBER}。
 * 用 {@link #isAtLeast(MembershipRole)} 做单调权限判定（如建门店要求至少 ADMIN）。
 * DB 存小写 dbValue，与 {@code organization.status='active'} 的 lower-case 约定一致。
 */
public enum MembershipRole {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member");

    private final String dbValue;

    MembershipRole(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 当前角色权限是否不低于给定角色（ordinal 越小权限越高）。 */
    public boolean isAtLeast(MembershipRole required) {
        return this.ordinal() <= required.ordinal();
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static MembershipRole fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("membership role is null");
        }
        String normalized = value.trim().toLowerCase();
        for (MembershipRole role : values()) {
            if (role.dbValue.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown membership role: " + value);
    }
}
