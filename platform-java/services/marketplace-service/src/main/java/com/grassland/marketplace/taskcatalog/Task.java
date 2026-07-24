package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推广任务（task-catalog MVP）。草场 Epic 4 Slice 4A（HLD 5.3）。
 *
 * <p>{@code ownerAccountId} = 发布者（断言 caller，merchant）；{@code organizationId} 逻辑引用 identity 的 organization
 * （跨服务无 FK，HLD database-per-service）；{@code status}/{@code contentForm}/{@code platform} 存小写字符串。
 * {@code maxSlots} 为名额上限（null=不限，Slice 4B）。本 slice 单版本、创建即 published；不可变版本/草稿留后续。
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
        Instant createdAt,
        Instant updatedAt
) {}
