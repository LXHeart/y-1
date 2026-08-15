package com.grassland.marketplace.taskcatalog;

/** Deterministic settlement decision produced from a frozen ladder and verified metric fact. */
public record CommissionSettlementPlan(
        String policyVersion,
        String metricKey,
        long metricValue,
        long settlementAmountCents,
        long reservedAmountCents) {

    public CommissionSettlementPlan {
        if (policyVersion == null || policyVersion.isBlank()
                || metricKey == null || metricKey.isBlank()) {
            throw new IllegalArgumentException("结算计划必须带策略版本和指标");
        }
        if (metricValue < 0 || settlementAmountCents < 0 || reservedAmountCents < 1) {
            throw new IllegalArgumentException("结算计划金额或指标值无效");
        }
        if (settlementAmountCents > reservedAmountCents) {
            throw new IllegalArgumentException("结算金额不能超过预留上限");
        }
    }

    public static CommissionSettlementPlan evaluate(CommissionLadder ladder, long metricValue,
                                                    long reservedAmountCents) {
        if (ladder == null) {
            throw new IllegalArgumentException("阶梯佣金策略不能为空");
        }
        ladder.validateReserve(reservedAmountCents);
        return new CommissionSettlementPlan(ladder.policyVersion(), ladder.metricKey(), metricValue,
                ladder.payoutFor(metricValue), reservedAmountCents);
    }
}
