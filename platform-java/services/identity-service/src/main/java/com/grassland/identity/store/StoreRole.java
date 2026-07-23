package com.grassland.identity.store;

/**
 * 门店粒度成员角色。草场身份域 Slice 2G（HLD store-membership）。
 *
 * <p>声明顺序即权限高低：{@link #MANAGER}（最高）&gt; {@link #STAFF}。用 {@link #isAtLeast(StoreRole)} 做单调判定。
 * DB 存小写 dbValue。门店成员管理 authz 当前复用 org 级 OrgAuthorization（MANAGER 级独立授权留后续）。
 */
public enum StoreRole {
    MANAGER("manager"),
    STAFF("staff");

    private final String dbValue;

    StoreRole(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 当前角色权限是否不低于给定角色（ordinal 越小权限越高）。 */
    public boolean isAtLeast(StoreRole required) {
        return this.ordinal() <= required.ordinal();
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static StoreRole fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("store role is null");
        }
        String normalized = value.trim().toLowerCase();
        for (StoreRole role : values()) {
            if (role.dbValue.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown store role: " + value);
    }
}
