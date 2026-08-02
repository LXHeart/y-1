package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 修订已发布任务请求体（{@code POST /api/tasks/{id}/revise}，GL-P1-TASK-001：编辑出新版本）。
 *
 * <p>仅 published 态可修订（controller 守卫）。{@code expectedVersion} 必填（乐观锁）。
 *
 * <p><b>刻意不含 bounty_cents/platform/content_form</b>——这些资金/物料条款一旦发布即冻结：
 * task_version 快照尚未被 accept/结算消费（仍重读可变 task 行），放任改赏金会改掉已报名履约的条款。
 * 修订只改 title/description/maxSlots/applicationDeadline（仅影响新报名）。全字段编辑待 snapshot-pinning。
 */
public record ReviseTaskRequest(
        int expectedVersion,
        String title,
        String description,
        Integer maxSlots,
        Instant applicationDeadline
) {
    public ReviseTaskRequest {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (maxSlots != null && maxSlots < 1) {
            throw new IllegalArgumentException("maxSlots must be >= 1");
        }
    }
}
