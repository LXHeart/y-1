package com.grassland.marketplace.reputation;

import java.util.List;

/** 单级阈值与可被下游消费的结构化权益。 */
public record ReputationLevelRule(
        int levelNumber,
        String level,
        String title,
        int minCompleted,
        double minCompletionRate,
        Double minAverageScore,
        boolean inviteOnly,
        boolean judgeEligible,
        int taskPriorityWeight,
        int settlementDelayDays,
        int commissionBonusBps,
        int aiQuotaMultiplierBps,
        boolean premiumSupport,
        List<String> benefits) {

    public ReputationLevelRule {
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
    }

    public RecommenderLevel recommenderLevel() {
        return RecommenderLevel.values()[levelNumber - 1];
    }
}
