package com.grassland.finance.escrow;

/**
 * 预留请求体（草场 Epic 4 Slice 4E）。{@code engagementRef} 必填（Saga 幂等键）；{@code amountCents} 须 {@code >= 1}。
 *
 * <p>{@code payeeAccountId}（可选）：这笔钱将来 capture 时该付给谁——只有 marketplace 知道该 engagement
 * 对应哪个报名推荐官，故由它传入。不传 = 无分账对象，capture 时钱留在平台账上（旧行为）。
 */
public record ReserveRequest(String engagementRef, Integer amountCents, String payeeAccountId) {
    public ReserveRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
        if (amountCents == null || amountCents < 1) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
    }
}
