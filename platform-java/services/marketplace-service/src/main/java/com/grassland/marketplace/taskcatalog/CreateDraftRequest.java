package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 创建任务草稿请求体（{@code POST /api/tasks/draft}，GL-P1-TASK-001 Stage 1）。
 *
 * <p>字段与 {@link CreateTaskRequest} 同（同 compact 校验），区别仅在语义：草稿不占发布额度、不需资金权限，
 * 草稿 tier（DRAFT）商家也可建。{@code applicationDeadline} 可在草稿阶段预设，发布时随快照冻结。
 */
public record CreateDraftRequest(
        String organizationId,
        String title,
        String description,
        String contentForm,
        String platform,
        Integer maxSlots,
        Long bountyCents,
        Instant applicationDeadline,
        Integer minRecommenderLevel,
        String storeId,
        TaskRequirements requirements,
        Integer autoAcceptMinLevel
) {
    public CreateDraftRequest(String organizationId, String title, String description, String contentForm,
                              String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
                              Integer minRecommenderLevel) {
        this(organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
                applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null);
    }

    public CreateDraftRequest {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required");
        }
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
        if (autoAcceptMinLevel != null && (autoAcceptMinLevel < 1 || autoAcceptMinLevel > 5)) {
            throw new IllegalArgumentException("autoAcceptMinLevel must be between 1 and 5");
        }
        requirements = TaskRequirements.normalize(requirements);
    }
}
