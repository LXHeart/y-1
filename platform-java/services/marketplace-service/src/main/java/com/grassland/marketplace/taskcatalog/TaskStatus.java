package com.grassland.marketplace.taskcatalog;

/**
 * 任务状态。草场 Epic 4 Slice 4A（HLD 5.3 task-catalog）。MVP 创建即 {@link #PUBLISHED}；
 * {@code draft}/{@code closed} 为后续草稿/关闭流转预留。DB 存小写 dbValue。
 */
public enum TaskStatus {
    DRAFT("draft"),
    PUBLISHED("published"),
    CLOSED("closed");

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
