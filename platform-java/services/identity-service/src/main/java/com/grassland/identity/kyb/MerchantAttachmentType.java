package com.grassland.identity.kyb;

/**
 * 商家附件类型。GL-P3-MERCHANT-001。
 */
public enum MerchantAttachmentType {
    BUSINESS_LICENSE("business_license"),
    LEGAL_PERSON_ID_FRONT("legal_person_id_front"),
    LEGAL_PERSON_ID_BACK("legal_person_id_back"),
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
        return this == BUSINESS_LICENSE || this == LEGAL_PERSON_ID_FRONT || this == LEGAL_PERSON_ID_BACK;
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

    /** 从 HTTP 请求解析。 */
    public static MerchantAttachmentType fromRequest(String value) {
        return fromDb(value);
    }
}
