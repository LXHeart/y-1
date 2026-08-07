package com.grassland.marketplace.reputation;

import java.util.List;

/** 管理端提交的完整单级策略。 */
public record ReputationLevelRuleInput(
        Integer levelNumber,
        String level,
        String title,
        Integer minCompleted,
        Double minCompletionRate,
        Double minAverageScore,
        Boolean inviteOnly,
        Boolean judgeEligible,
        Integer taskPriorityWeight,
        Integer settlementDelayDays,
        Integer commissionBonusBps,
        Integer aiQuotaMultiplierBps,
        Boolean premiumSupport,
        List<String> benefits) {}
