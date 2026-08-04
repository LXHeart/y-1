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

    /**
     * outbox 事件的 aggregate type。
     *
     * <p>此前用 {@code dbValue().substring(0,1).toUpperCase() + substring(1)} 拼，
     * 得到的是 {@code "Merchant_profile"}（下划线残留）——与其余事件的 PascalCase 不一致。
     */
    public String aggregateType() {
        return switch (this) {
            case MERCHANT_PROFILE -> "MerchantProfile";
            case STORE_PROFILE -> "StoreProfile";
            case WITHDRAWAL_ACCOUNT -> "WithdrawalAccount";
        };
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
