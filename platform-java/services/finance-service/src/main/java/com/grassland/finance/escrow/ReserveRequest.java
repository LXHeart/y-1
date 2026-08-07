package com.grassland.finance.escrow;

import java.util.UUID;

/**
 * 预留请求体（草场 Epic 4 Slice 4E）。{@code engagementRef} 必填（Saga 幂等键）；{@code amountCents} 须 {@code >= 1}。
 *
 * <p>{@code payeeAccountId}（可选）：这笔钱将来 capture 时该付给谁——只有 marketplace 知道该 engagement
 * 对应哪个报名推荐官，故由它传入。不传 = 无分账对象，capture 时钱留在平台账上（旧行为）。
 * {@code commissionBonusBps} 只能由 marketplace 服务授予，并在 reserve 时冻结为补贴金额。
 */
public record ReserveRequest(String engagementRef, Long amountCents, String payeeAccountId,
                             Integer commissionBonusBps) {
    public ReserveRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
        if (engagementRef.length() > 256) {
            throw new IllegalArgumentException("engagementRef must not exceed 256 characters");
        }
        if (amountCents == null || amountCents < 1) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
        commissionBonusBps = commissionBonusBps == null ? 0 : commissionBonusBps;
        if (commissionBonusBps < 0 || commissionBonusBps > CommissionBonusPolicy.MAX_BPS) {
            throw new IllegalArgumentException("commissionBonusBps must be within [0, 10000]");
        }
        if (commissionBonusBps > 0 && (payeeAccountId == null || payeeAccountId.isBlank())) {
            throw new IllegalArgumentException("payeeAccountId is required when commissionBonusBps is positive");
        }
        if (payeeAccountId != null && !payeeAccountId.isBlank()) {
            try {
                UUID.fromString(payeeAccountId);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("payeeAccountId must be a UUID", invalid);
            }
        }
    }
}
