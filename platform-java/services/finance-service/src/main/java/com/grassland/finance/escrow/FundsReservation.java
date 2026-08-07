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
        /** 收款推荐官（V3）。由 marketplace 在预留时传入；null = 无分账对象（存量预留或非撮合场景）。 */
        String payeeAccountId,
        /** capture 时实际打入推荐官钱包的总额（原赏金 - 平台抽成 + 平台补贴）；未 capture 或无收款人时为 null。 */
        Long payoutCents,
        /** reserve 时冻结的等级佣金加成（基点）。 */
        int commissionBonusBps,
        /** 平台承担的佣金补贴分，按原任务赏金计算并在 reserve 时冻结。 */
        long commissionBonusCents,
        Instant createdAt,
        Instant updatedAt
) {}
