package com.grassland.identity.kyb;

/**
 * KYB 审核类型。GL-P3-MERCHANT-001。
 */
public enum KybVerificationType {
    MERCHANT_PROFILE("merchant_profile"),
    STORE_PROFILE("store_profile"),
    WITHDRAWAL_ACCOUNT("withdrawal_account");

    private final String dbValue;

    KybVerificationType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static KybVerificationType fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("KYB verification type is null");
        }
        String normalized = value.trim().toLowerCase();
        for (KybVerificationType t : values()) {
            if (t.dbValue.equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown KYB verification type: " + value);
    }
}
