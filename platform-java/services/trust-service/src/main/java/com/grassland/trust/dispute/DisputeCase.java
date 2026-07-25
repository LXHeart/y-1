package com.grassland.trust.dispute;

import java.time.Instant;

/**
 * 争议案件（dispute-case，HLD 5.5）。草场 Epic 6 Slice 6A。
 *
 * <p>{@code engagementRef} 跨服务引用 marketplace application/engagement（database-per-service 无 FK）；
 * {@code organizationId} 冗余供鉴权/查询；{@code openedByRole}=merchant/recommender（HLD 10.5 Party）；
 * {@code status} open/decided（极简；审判状态机留后续）；{@code decision} 手动裁决（本 slice 不动钱）。
 */
public record DisputeCase(
        String id,
        String engagementRef,
        String organizationId,
        String openedByAccountId,
        String openedByRole,
        String status,
        String reason,
        String decision,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt
) {}
