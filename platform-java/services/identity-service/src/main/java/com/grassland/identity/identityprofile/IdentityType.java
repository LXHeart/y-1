package com.grassland.identity.identityprofile;

/**
 * 可开通的身份类型。草场身份域 Slice 2G（HLD 1.3 事实 1/2）。
 *
 * <p>{@link #MERCHANT}（商家）/ {@link #RECOMMENDER}（推荐官）。消费者是所有账号的默认场景，
 * <b>不是</b>一种可开通身份（不在此枚举；活动身份为 NULL 即消费者）。
 * DB 存小写 dbValue。
 */
public enum IdentityType {
    MERCHANT("merchant"),
    RECOMMENDER("recommender");

    private final String dbValue;

    IdentityType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static IdentityType fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("identity type is null");
        }
        String normalized = value.trim().toLowerCase();
        for (IdentityType type : values()) {
            if (type.dbValue.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown identity type: " + value);
    }
}
