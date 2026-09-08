package com.grassland.marketplace.benefit;

import java.time.Instant;

/**
 * 体验权益单（任务书 #96 C96-03 / D96-05）：体验合作的独立权益事实，与押金字段解耦展示（§8）。
 *
 * <p>状态（§4.2）：{@code pending_booking}（待预约）→ {@code booked}（已预约）→
 * {@code fulfilled}（已兑现）→ 商家确认（{@code fulfilledConfirmedBy}）；商家失约主张成立 →
 * {@code merchant_defaulted}；任一方取消 → {@code cancelled}。
 *
 * <p>失约三时间戳（TC96-012 计时暂停可解释）：{@code defaultClaimedAt} 推荐官举证主张；
 * {@code defaultDeadlineAt} 商家回应窗截止；{@code defaultResolvedAt}/{@code defaultResolution}
 * 结局（defaulted=到期未回应自动成立 / denied=商家限时否认）。成立时交付/补救截止顺延的
 * 解释区间 = [claimedAt, resolvedAt]（即主张到成立的回应窗），权益行可查。
 */
public record ExperienceBenefit(
        String id,
        String applicationId,
        String storeId,
        String itemsJson,
        Instant bookingWindow,
        Instant fulfilledAt,
        String fulfilledConfirmedBy,
        Instant defaultClaimedAt,
        Instant defaultDeadlineAt,
        Instant defaultResolvedAt,
        String defaultResolution,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_PENDING_BOOKING = "pending_booking";
    public static final String STATUS_BOOKED = "booked";
    public static final String STATUS_FULFILLED = "fulfilled";
    public static final String STATUS_MERCHANT_DEFAULTED = "merchant_defaulted";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String RESOLUTION_DEFAULTED = "defaulted";
    public static final String RESOLUTION_DENIED = "denied";

    public boolean defaultClaimOpen() {
        return defaultClaimedAt != null && defaultResolvedAt == null;
    }

    public boolean consumed() {
        return STATUS_FULFILLED.equals(status);
    }
}
