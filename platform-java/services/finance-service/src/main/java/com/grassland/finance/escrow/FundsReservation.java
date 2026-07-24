package com.grassland.finance.escrow;

import java.time.Instant;

/**
 * 资金预留（escrow，HLD 5.4「预留/释放」）。草场 Epic 4 Slice 4E。
 *
 * <p>{@code accountId} 同库 FK 引用 finance_account；{@code organizationId} 冗余（鉴权/查询）；{@code engagementRef}
 * 跨服务引用 marketplace application/engagement（database-per-service 无 FK）；{@code status} reserved/released/captured
 * （存 String，house style）。reserve 扣账户余额、release 还原；{@code UNIQUE(engagement_ref)} 保证 Saga 重试幂等。
 */
public record FundsReservation(
        String id,
        String accountId,
        String organizationId,
        String engagementRef,
        long amountCents,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
