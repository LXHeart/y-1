package com.grassland.marketplace.taskcatalog;

/**
 * 交付物状态。{@link #SUBMITTED} 是唯一「占位」状态（partial unique 索引只约束它）：
 * 被退回后不占位，推荐官可以改好重交。
 */
public enum SubmissionStatus {
    SUBMITTED("submitted"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    private final String dbValue;

    SubmissionStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
