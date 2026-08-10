package com.grassland.identity.kyb;

/**
 * 商家附件类型。GL-P3-MERCHANT-001。
 */
public enum MerchantAttachmentType {
    BUSINESS_LICENSE("business_license"),
    LEGAL_PERSON_ID_FRONT("legal_person_id_front"),
    LEGAL_PERSON_ID_BACK("legal_person_id_back"),
    INDUSTRY_LICENSE("industry_license"),
    FINANCIAL_QUALIFICATION("financial_qualification"),
    STORE_PHOTO("store_photo"),
    OTHER("other");

    private final String dbValue;

    MerchantAttachmentType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 是否证件类附件（证件类每种只能有一个）。 */
    public boolean isDocumentType() {
        return this == BUSINESS_LICENSE || this == LEGAL_PERSON_ID_FRONT || this == LEGAL_PERSON_ID_BACK
                || this == INDUSTRY_LICENSE || this == FINANCIAL_QUALIFICATION;
    }

    /** 商家主体 KYB 通过后仍可补传、用于权限升级的证照。 */
    public boolean isPermissionSupplement() {
        return this == INDUSTRY_LICENSE || this == FINANCIAL_QUALIFICATION;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static MerchantAttachmentType fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("merchant attachment type is null");
        }
        String normalized = value.trim().toLowerCase();
        for (MerchantAttachmentType t : values()) {
            if (t.dbValue.equals(normalized)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown merchant attachment type: " + value);
    }

    /**
     * 从 HTTP 请求解析。非法值抛 {@link com.grassland.identity.auth.IdentityException} 400——
     * 客户端传错类型是请求错误，不该冒成 500。
     */
    public static MerchantAttachmentType fromRequest(String value) {
        try {
            return fromDb(value);
        } catch (IllegalArgumentException e) {
            throw new com.grassland.identity.auth.IdentityException(400, "附件类型无效：" + value);
        }
    }

    /** 提交 KYB 审核前必须齐备的证件类附件。 */
    public static java.util.List<MerchantAttachmentType> requiredForSubmission() {
        return java.util.List.of(BUSINESS_LICENSE, LEGAL_PERSON_ID_FRONT, LEGAL_PERSON_ID_BACK);
    }

    /** 中文名（用于 400 错误里列出缺失材料）。 */
    public String displayName() {
        return switch (this) {
            case BUSINESS_LICENSE -> "营业执照";
            case LEGAL_PERSON_ID_FRONT -> "法人证件正面";
            case LEGAL_PERSON_ID_BACK -> "法人证件反面";
            case INDUSTRY_LICENSE -> "行业许可证";
            case FINANCIAL_QUALIFICATION -> "财务资质";
            case STORE_PHOTO -> "门店照片";
            case OTHER -> "其他材料";
        };
    }
}
