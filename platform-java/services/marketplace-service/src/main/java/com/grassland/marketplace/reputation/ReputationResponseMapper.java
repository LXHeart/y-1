package com.grassland.marketplace.reputation;

import java.util.LinkedHashMap;
import java.util.Map;

/** reputation HTTP 边界的稳定字段映射。 */
final class ReputationResponseMapper {

    private ReputationResponseMapper() {}

    static Map<String, Object> policy(ReputationPolicy policy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", policy.version());
        data.put("updatedAt", policy.updatedAt() == null ? null : policy.updatedAt().toString());
        data.put("levels", policy.levels().stream().map(ReputationResponseMapper::rule).toList());
        return data;
    }

    static Map<String, Object> rule(ReputationLevelRule rule) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("levelNumber", rule.levelNumber());
        data.put("level", rule.level());
        data.put("title", rule.title());
        data.put("minCompleted", rule.minCompleted());
        data.put("minCompletionRate", rule.minCompletionRate());
        data.put("minAverageScore", rule.minAverageScore());
        data.put("inviteOnly", rule.inviteOnly());
        data.put("judgeEligible", rule.judgeEligible());
        data.put("taskPriorityWeight", rule.taskPriorityWeight());
        data.put("settlementDelayDays", rule.settlementDelayDays());
        data.put("commissionBonusBps", rule.commissionBonusBps());
        data.put("aiQuotaMultiplierBps", rule.aiQuotaMultiplierBps());
        data.put("premiumSupport", rule.premiumSupport());
        data.put("benefits", rule.benefits());
        return data;
    }

    static Map<String, Object> reputation(ReputationSnapshot snapshot, boolean includeAdministration) {
        ReputationStats stats = snapshot.stats();
        ReputationEvaluation evaluation = snapshot.evaluation();
        ReputationLevelRule effectiveRule = snapshot.policy().ruleFor(evaluation.effectiveLevel());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", snapshot.accountId());
        data.put("level", evaluation.effectiveLevel().code());
        data.put("levelTitle", effectiveRule.title());
        data.put("calculatedLevel", evaluation.calculatedLevel().code());
        data.put("effectiveLevel", evaluation.effectiveLevel().code());
        data.put("levelNumber", evaluation.effectiveLevel().ordinal() + 1);
        data.put("judgeEligible", evaluation.judgeEligible());
        data.put("policyVersion", snapshot.policy().version());
        data.put("taskPriorityWeight", evaluation.taskPriorityWeight());
        data.put("settlementDelayDays", evaluation.settlementDelayDays());
        data.put("commissionBonusBps", evaluation.commissionBonusBps());
        data.put("aiQuotaMultiplierBps", evaluation.aiQuotaMultiplierBps());
        data.put("premiumSupport", evaluation.premiumSupport());
        data.put("benefits", evaluation.benefits());
        data.put("acceptedCount", stats.acceptedCount());
        data.put("completedCount", stats.completedCount());
        data.put("merchantCancelledCount", stats.merchantCancelledCount());
        data.put("rejectedCount", stats.rejectedCount());
        data.put("withdrawnCount", stats.withdrawnCount());
        data.put("terminalCount", stats.terminalCount());
        data.put("completionRate", round(stats.completionRate(), 4));
        data.put("ratingCount", stats.ratingCount());
        data.put("averageScore", stats.averageScore() == null ? null : round(stats.averageScore(), 2));
        data.put("averageResponseSeconds", stats.averageResponseSeconds() == null
                ? null : Math.round(stats.averageResponseSeconds()));
        data.put("lastActiveAt", stats.lastActiveAt() == null ? null : stats.lastActiveAt().toString());
        data.put("inactiveDowngraded", evaluation.inactiveDowngraded());
        if (includeAdministration) {
            data.put("lv5Admitted", snapshot.admission().admitted());
            data.put("admissionVersion", snapshot.admission().version());
            data.put("admissionUpdatedBy", snapshot.admission().updatedBy());
            data.put("admissionNote", snapshot.admission().note());
            data.put("admissionUpdatedAt", snapshot.admission().updatedAt() == null
                    ? null : snapshot.admission().updatedAt().toString());
        }
        return data;
    }

    static Map<String, Object> admission(Lv5Admission admission) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", admission.accountId());
        data.put("admitted", admission.admitted());
        data.put("version", admission.version());
        data.put("updatedBy", admission.updatedBy());
        data.put("note", admission.note());
        data.put("updatedAt", admission.updatedAt() == null ? null : admission.updatedAt().toString());
        return data;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
