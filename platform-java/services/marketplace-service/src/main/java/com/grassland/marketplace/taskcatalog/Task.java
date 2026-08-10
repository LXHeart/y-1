package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推广任务（task-catalog）。草场 Epic 4 Slice 4A（HLD 5.3）+ GL-P1-TASK-001 Stage 1 生命周期。
 *
 * <p>{@code ownerAccountId} = 发布者（断言 caller，merchant）；{@code organizationId} 逻辑引用 identity 的 organization
 * （跨服务无 FK，HLD database-per-service）；{@code status}/{@code contentForm}/{@code platform} 存小写字符串。
 * {@code maxSlots} 为名额上限（null=不限，Slice 4B）。
 *
 * <p>Stage 1 生命周期字段：
 * <ul>
 *   <li>{@code version} 乐观锁计数器（draft 编辑 / publish / close / cancel 每次 +1）；</li>
 *   <li>{@code applicationDeadline} 仅建模 PRD「指定时间」截止（apply 时判，null=无时间截止）；</li>
 *   <li>{@code publishedAt} 进入 published 的时刻（发布额度/月度统计按它计）；</li>
 *   <li>{@code cancelledAt} 取消时刻。</li>
 * </ul>
 * 不可变要求快照见 {@code task_version} 表（publish 时落一行），不在此 record。
 */
public record Task(
        String id,
        String ownerAccountId,
        String organizationId,
        String title,
        String description,
        String status,
        String contentForm,
        String platform,
        Integer maxSlots,
        Long bountyCents,
        Instant createdAt,
        Instant updatedAt,
        int version,
        Instant applicationDeadline,
        Instant publishedAt,
        Instant cancelledAt,
        int minRecommenderLevel,
        String storeId
) {
    public Task(String id, String ownerAccountId, String organizationId, String title, String description,
                String status, String contentForm, String platform, Integer maxSlots, Long bountyCents,
                Instant createdAt, Instant updatedAt, int version, Instant applicationDeadline,
                Instant publishedAt, Instant cancelledAt) {
        this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
                bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt, 1, null);
    }

    public Task(String id, String ownerAccountId, String organizationId, String title, String description,
                String status, String contentForm, String platform, Integer maxSlots, Long bountyCents,
                Instant createdAt, Instant updatedAt, int version, Instant applicationDeadline,
                Instant publishedAt, Instant cancelledAt, int minRecommenderLevel) {
        this(id, ownerAccountId, organizationId, title, description, status, contentForm, platform, maxSlots,
                bountyCents, createdAt, updatedAt, version, applicationDeadline, publishedAt, cancelledAt,
                minRecommenderLevel, null);
    }
}
