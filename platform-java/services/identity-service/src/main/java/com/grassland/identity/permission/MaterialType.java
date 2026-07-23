package com.grassland.identity.permission;

/**
 * 商家权限升级的结构化材料类型。草场身份域 Slice 2L（HLD D-05「材料 schema」）。
 *
 * <p>申请体 {@code materials} 为 {@code Map<String,String>}，键为 dbValue，值为证照号/URL 等文本。
 * {@link PermissionMaterialPolicy} 按 tier(+行业) 决定必填集合。
 */
public enum MaterialType {
    BUSINESS_LICENSE("business_license"),
    LEGAL_REPRESENTATIVE("legal_representative"),
    FINANCIAL_QUALIFICATION("financial_qualification"),
    INDUSTRY_LICENSE("industry_license"),
    CONTACT_INFO("contact_info");

    private final String dbValue;

    MaterialType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB/请求字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static MaterialType fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("material type is null");
        }
        String normalized = value.trim().toLowerCase();
        for (MaterialType type : values()) {
            if (type.dbValue.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown material type: " + value);
    }
}
