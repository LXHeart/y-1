package com.grassland.marketplace.taskcatalog;

/**
 * 任务状态。草场 Epic 4 Slice 4A（HLD 5.3 task-catalog）+ GL-P1-TASK-001 Stage 1 生命周期。
 *
 * <p>状态机：{@code draft} →（publish）{@code published} →（close）{@code closed}；
 * {@code draft}/{@code published} →（cancel）{@code cancelled}。{@code closed}/{@code cancelled} 为终态
 * （close=停止新报名但既有履约继续；cancel=任务取消）。DB 存小写 dbValue。
 */
public enum TaskStatus {
    DRAFT("draft"),
    PUBLISHED("published"),
    CLOSED("closed"),
    CANCELLED("cancelled");

    private final String dbValue;

    TaskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；null/空 → PUBLISHED；非法值抛 {@link IllegalArgumentException}。 */
    public static TaskStatus fromDb(String value) {
        if (value == null || value.isBlank()) {
            return PUBLISHED;
        }
        String normalized = value.trim().toLowerCase();
        for (TaskStatus status : values()) {
            if (status.dbValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown task status: " + value);
    }
}
