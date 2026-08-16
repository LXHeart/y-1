package com.grassland.finance.freebie;

import java.time.Instant;

/**
 * 霸王餐押金托管行（ADR-D12 / V18）。资金方向与 {@code funds_reservation}（商家出资）相反：
 * 出资方 = 推荐官（押金从其钱包扣、退款回其钱包），商家是补偿的收款方。
 *
 * <p>生命周期：{@code reserved} →（达标）{@code refunded} 退还推荐官 /（未达标或商家获判）{@code compensated} 入商家 org。
 * 状态守卫与幂等语义镜像 {@code funds_reservation}（engagement_ref 唯一 = Saga 重试安全）。
 */
public record FreebieEscrow(
        String id,
        String engagementRef,
        String recommenderAccountId,
        String taskOwnerAccountId,
        String organizationId,
        long amountCents,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_RESERVED = "reserved";
    public static final String STATUS_REFUNDED = "refunded";
    public static final String STATUS_COMPENSATED = "compensated";
}
