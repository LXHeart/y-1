package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 编辑任务草稿请求体（{@code PUT /api/tasks/{id}}，GL-P1-TASK-001 Stage 1）。
 *
 * <p>仅 draft 态可编辑（controller 守卫）。{@code expectedVersion} 必填（乐观锁：等于客户端读取时的 task.version），
 * 服务端 guarded UPDATE {@code WHERE version=:expected}，冲突 → 409。{@code title} 必填；其余可空字段 null=清空。
 */
public record UpdateTaskRequest(
        int expectedVersion,
        String title,
        String description,
        String contentForm,
        String platform,
        Integer maxSlots,
        Long bountyCents,
        Instant applicationDeadline,
        Integer minRecommenderLevel,
        TaskRequirements requirements
) {
    public UpdateTaskRequest {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (maxSlots != null && maxSlots < 1) {
            throw new IllegalArgumentException("maxSlots must be >= 1");
        }
        if (bountyCents != null && bountyCents < 0) {
            throw new IllegalArgumentException("bountyCents must be >= 0");
        }
        if (minRecommenderLevel != null && (minRecommenderLevel < 1 || minRecommenderLevel > 5)) {
            throw new IllegalArgumentException("minRecommenderLevel must be between 1 and 5");
        }
    }
}
