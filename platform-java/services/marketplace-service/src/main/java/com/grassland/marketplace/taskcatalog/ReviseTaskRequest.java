package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 修订已发布任务请求体（{@code POST /api/tasks/{id}/revise}，GL-P1-TASK-001：编辑出新版本）。
 *
 * <p>仅 published 态可修订（controller 守卫）。{@code expectedVersion} 必填（乐观锁）。全字段可改——
 * accept/结算已读 {@code task_application.bounty_cents} 快照（V14 snapshot-pinning），故修订 task 赏金/平台
 * 只影响**新报名**（新 app 冻新值），已 accept 的履约仍按其 accept 时的快照结算，不会被改动。
 *
 * <p>赏金变更仍受 tier 上限约束（controller {@code enforceBountyTierGate}：bounty ≤ 本组织单笔上限、资金型须有交易权限），
 * 与发布同口径——避免商家借修订把赏金抬到 tier 之上。
 */
public record ReviseTaskRequest(
        int expectedVersion,
        String title,
        String description,
        String contentForm,
        String platform,
        Integer maxSlots,
        Long bountyCents,
        Instant applicationDeadline,
        Integer minRecommenderLevel,
        TaskRequirements requirements,
        Integer autoAcceptMinLevel
) {
    public ReviseTaskRequest {
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
    }
}
