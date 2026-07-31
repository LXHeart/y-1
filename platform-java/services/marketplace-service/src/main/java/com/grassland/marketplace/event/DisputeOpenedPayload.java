package com.grassland.marketplace.event;

/**
 * trust {@code DisputeOpened} 载荷（草场「争议对方通知」缺口补全）。
 *
 * <p>{@code disputeId}/{@code engagementRef} 必填；{@code openedByAccountId}/{@code openedByRole}
 * 决定对方是谁（merchant 开 → 对方=推荐官；recommender 开 → 对方=任务归属商家）；{@code organizationId} 可选。
 * trust 只存开争议者，对方账号仅经 {@code engagementRef}（= marketplace applicationId）间接引用——
 * marketplace 自有 task+application 两表，在本服务内解析对方，不反向调 trust。
 */
public record DisputeOpenedPayload(
        String disputeId,
        String engagementRef,
        String organizationId,
        String openedByAccountId,
        String openedByRole) {}
