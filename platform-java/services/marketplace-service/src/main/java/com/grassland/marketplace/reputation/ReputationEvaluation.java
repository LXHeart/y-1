package com.grassland.marketplace.reputation;

import java.util.List;

/** 某账号在一个明确策略版本下的等级和权益结果。 */
public record ReputationEvaluation(
        RecommenderLevel calculatedLevel,
        RecommenderLevel effectiveLevel,
        boolean judgeEligible,
        int taskPriorityWeight,
        int settlementDelayDays,
        int commissionBonusBps,
        int aiQuotaMultiplierBps,
        boolean premiumSupport,
        boolean inactiveDowngraded,
        List<String> benefits) {

    public ReputationEvaluation {
        benefits = List.copyOf(benefits);
    }
}
