package com.grassland.identity.store;

/** 门店资料 KYB 状态。 */
public enum StoreProfileStatus {
    DRAFT("draft"),
    PENDING("pending"),
    UNDER_REVIEW("under_review"),
    APPROVED("approved"),
    REJECTED("rejected"),
    INACTIVE("inactive");

    private final String dbValue;

    StoreProfileStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean isEditable() {
        return this == DRAFT || this == REJECTED || this == INACTIVE;
    }

    public boolean canSubmit() {
        return this == DRAFT || this == REJECTED;
    }

    public boolean isUnderReview() {
        return this == PENDING || this == UNDER_REVIEW;
    }

    public static StoreProfileStatus fromDb(String value) {
        if (value != null) {
            for (StoreProfileStatus status : values()) {
                if (status.dbValue.equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("unknown store profile status: " + value);
    }
}
