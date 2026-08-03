package com.grassland.identity.kyb;

/**
 * 收款账户类型。GL-P3-MERCHANT-001。
 */
public enum WithdrawalAccountType {
    BANK_CARD("bank_card"),
    ALIPAY("alipay"),
    WECHAT("wechat");

    private final String dbValue;

    WithdrawalAccountType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static WithdrawalAccountType fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("withdrawal account type is null");
        }
        String normalized = value.trim().toLowerCase();
        for (WithdrawalAccountType t : values()) {
            if (t.dbValue.equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown withdrawal account type: " + value);
    }

    /** 从 HTTP 请求解析。 */
    public static WithdrawalAccountType fromRequest(String value) {
        return fromDb(value);
    }
}
