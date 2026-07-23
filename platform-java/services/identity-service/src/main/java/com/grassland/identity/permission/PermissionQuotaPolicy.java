package com.grassland.identity.permission;

import com.grassland.identity.organization.PermissionTier;

/**
 * 商家 tier 的额度策略（HLD D-05「额度」）。纯逻辑 + 静态策略表。
 *
 * <p>identity 只暴露策略（限额上限）；硬限额/扣减执行留 marketplace（按 maxActiveTasks/maxMonthlyTasks）
 * 与 finance（按 maxTxAmountCents）。额度「已用/剩余」实时计数需下游喂用量，本 slice 不做。
 */
public final class PermissionQuotaPolicy {

    private PermissionQuotaPolicy() {}

    /** 某 tier 的额度上限。 */
    public record TierQuota(int maxActiveTasks, int maxMonthlyTasks, long maxTxAmountCents) {}

    /** DRAFT 不可发布/交易；BASIC_PUBLISH 可发布不可交易；FINANCE_TRANSACTION 全量。 */
    public static TierQuota quotaFor(PermissionTier tier) {
        return switch (tier) {
            case DRAFT -> new TierQuota(0, 0, 0);
            case BASIC_PUBLISH -> new TierQuota(5, 20, 0);
            case FINANCE_TRANSACTION -> new TierQuota(50, 500, 10_000_000);
        };
    }
}
