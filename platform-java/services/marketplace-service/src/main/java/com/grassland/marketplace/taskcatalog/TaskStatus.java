package com.grassland.marketplace.taskcatalog;

/**
 * 任务状态。草场 Epic 4 Slice 4A（HLD 5.3 task-catalog）+ GL-P1-TASK-001 Stage 1 生命周期 +
 * GL-P2-ADMIN-003 全审政策。
 *
 * <p>状态机（全审政策）：{@code draft} →（submit）{@code pending_review} →（approve）{@code published}
 * →（close）{@code closed}；{@code pending_review} →（reject）可修改后重新提交；
 * {@code draft}/{@code pending_review}/{@code published} →（cancel）{@code cancelled}。
 * {@code closed}/{@code cancelled} 为终态。
 *
 * <p>兼容路径 {@code POST /api/tasks}（创建即提交）直接落 {@code pending_review}（不再直接 published）。
 */
public enum TaskStatus {
    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
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
