package com.grassland.trust.dispute;

/**
 * 争议状态（草场 Epic 6 Slice 6A，极简）。{ @link DisputeCaseStatus#OPEN } 阻塞结算；
 * {@link #DECIDED} 终态（手动裁决）。审判（投票/平票/上诉）状态机留后续 slice。
 */
public enum DisputeCaseStatus {
    OPEN("open"),
    DECIDED("decided");

    private final String dbValue;

    DisputeCaseStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
